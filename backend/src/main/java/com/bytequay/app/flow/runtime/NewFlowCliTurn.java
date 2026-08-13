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
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.cli.CliAgentArgv;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.StreamJsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Runs one agent turn as a CLI subprocess, and returns what an in-JVM turn would
 * have returned.
 *
 * <p><b>Why this is a separate transport and not a separate agent.</b> The role,
 * its prompt and its tools are the program's decisions and do not change when a
 * workspace names a CLI engine. What changes is only how the turn is carried: an
 * API turn hands its {@link ToolExecutor} to the turn runner, while this one
 * serves that same executor over {@link NewFlowAgentToolBridge} and spends its
 * time draining a pipe. Both hand back a {@link TurnResult}, so the bodies above
 * cannot tell which ran.
 *
 * <p><b>The order in here is load-bearing.</b> Containment goes on before the
 * process starts, the process group is persisted before the prompt is delivered,
 * and the group is proven absent before the method returns — which is what keeps
 * the writer fence held until the agent is really gone. Reordering any of the
 * three turns a proof into an assumption:
 *
 * <ul>
 *   <li>containment after launch would leave a window with working push
 *       credentials in the environment;
 *   <li>a group id persisted at the end would be lost by exactly the crash that
 *       makes it matter, leaving a live agent nothing can find;
 *   <li>returning before burial would let the successor writer into a worktree
 *       the previous agent is still editing.
 * </ul>
 *
 * <p>The agent's outcome is not read from anything it says. It edits the
 * worktree directly, so the caller derives the result from Git state the same
 * way it does for a body that finished normally; what this returns is the
 * transcript's final text and the turn's accounting, not a verdict.
 */
final class NewFlowCliTurn
{
    private static final Logger log = LoggerFactory.getLogger(NewFlowCliTurn.class);
    /** Every turn's first and only user message; the work is in the prompt. */
    private static final String USER_MESSAGE =
            "Work only on the exact program-selected subject.";
    /** The pipe is already closed by burial; this only bounds a stuck reader. */
    private static final long READER_JOIN_TIMEOUT = 5_000;
    /** Longer than the two-hour local-check ceiling plus inspection overhead. */
    private static final String CLAUDE_MCP_TOOL_TIMEOUT_MILLIS = "7500000";
    private static final int DIAGNOSTIC_LIMIT = 16 * 1024;

    /** Appended to every CLI turn's prompt; transport details are added below. */
    private static final String TOOLING_GUIDANCE =
            "Your own built-in tools are available for this turn; decide which"
            + " are useful. The supplied bytequay tools are the recommended"
            + " way to interact with this worktree, and their terminal"
            + " call is the only completion signal the program reads — always"
            + " finish with the exact terminal tool the instructions name."
            + " Never run git commit or git push yourself; the program owns"
            + " the repository history. Never disable or escape the supplied"
            + " sandbox; if it blocks an operation, use a bytequay tool or"
            + " continue another way.";

    private final FlowRuntime runtime;
    private final NewFlowAgentToolBridge bridge;
    private final NewFlowAgentPermissions permissions;
    private final ObjectMapper mapper;
    private final int serverPort;

    NewFlowCliTurn(
            FlowRuntime runtime,
            NewFlowAgentToolBridge bridge,
            NewFlowAgentPermissions permissions,
            ObjectMapper mapper,
            int serverPort)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.bridge = requireNonNull(bridge, "bridge is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.serverPort = serverPort;
    }

    /**
     * What the program writes down about a turn it is running.
     *
     * <p>Callbacks rather than a runtime handle because the claim, fence and
     * attempt these are written against belong to the supervisor, and the body
     * is handed only a capability.
     */
    interface TurnJournal
    {
        /** Called before the turn is given any work. */
        void group(long agentPid, long agentPgid, Instant agentStartedAt);

        /**
         * Called once the turn has ended, with what it spent and the vendor's
         * handle for continuing it.
         */
        void usage(
                String providerSessionId,
                long tokensIn,
                long tokensOut,
                long costMilliUsd);

        /** Best-effort live activity; never part of the durable outcome. */
        default void activity(StreamEvent event) {}
    }

    /**
     * @param providerSessionId the vendor's own session id, for resuming this
     *         role's next turn; null when the CLI never reported one
     */
    record Outcome(
            TurnResult turn,
            String providerSessionId,
            boolean providerWorkStarted) {}

    /**
     * A writing turn, in the Task's own worktree and contained there.
     */
    Outcome runInWorktree(
            String runId,
            NewFlowAgentLaunches.Binding binding,
            ArrayNode tools,
            String systemPrompt,
            ToolExecutor executor,
            Path worktree,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        return runInWorktree(
                runId, runId, binding, tools, systemPrompt, executor,
                worktree, journal, interrupted);
    }

    /**
     * A writing turn whose approval card belongs to a containing product run.
     * The agent run id still owns the bridge, resume handle, usage and fence;
     * only the human-facing approval lookup uses {@code permissionOwnerId}.
     */
    Outcome runInWorktree(
            String runId,
            String permissionOwnerId,
            NewFlowAgentLaunches.Binding binding,
            ArrayNode tools,
            String systemPrompt,
            ToolExecutor executor,
            Path worktree,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(permissionOwnerId, "permissionOwnerId is null");
        Path scratch;
        try {
            // A linked worktree has a .git file, not a directory. Ask Git for
            // the worktree's actual private git directory instead of guessing
            // from checkout layout.
            scratch = CliWriterContainment.gitDirectory(worktree)
                    .resolve("bytequay-cli")
                    .resolve(runId);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "could not resolve the agent worktree git directory",
                    failure);
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while resolving the worktree git directory",
                    interruption);
        }
        return run(
                runId, permissionOwnerId, binding, tools, systemPrompt,
                executor, worktree,
                scratch, false, journal, interrupted);
    }

    /**
     * A read-only turn, which has no worktree at all.
     *
     * <p>A reviewer or learner reads through its tools — the immutable git
     * objects, the frozen CI logs — and never through the filesystem, so it is
     * given a directory with nothing in it rather than the repository. That is
     * stronger than containment, not weaker: there is no remote to poison
     * because there is no repository to push from, and the environment is
     * scrubbed of credentials all the same.
     */
    Outcome runReadOnly(
            String runId,
            NewFlowAgentLaunches.Binding binding,
            ArrayNode tools,
            String systemPrompt,
            ToolExecutor executor,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        Path scratch;
        try {
            scratch = Files.createTempDirectory("bytequay-readonly-agent-");
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "could not create the agent's scratch directory", failure);
        }
        try {
            return run(
                    runId, runId, binding, tools, systemPrompt, executor, scratch,
                    scratch, true, journal, interrupted);
        }
        finally {
            deleteRecursively(scratch);
        }
    }

    private Outcome run(
            String runId,
            String permissionOwnerId,
            NewFlowAgentLaunches.Binding binding,
            ArrayNode tools,
            String systemPrompt,
            ToolExecutor executor,
            Path directory,
            Path scratch,
            boolean readOnly,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        requireNonNull(binding, "binding is null");
        if (binding.isApi()) {
            throw new IllegalArgumentException(
                    "an API binding must not take the CLI turn path");
        }
        requireNonNull(tools, "tools is null");
        requireNonNull(executor, "executor is null");
        requireNonNull(journal, "journal is null");

        CliAgentArgv.Vendor vendor = vendor(binding);
        ArrayNode bridgeTools = tools.deepCopy();
        ToolExecutor bridgedExecutor = executor;
        if (vendor == CliAgentArgv.Vendor.CLAUDE_CODE) {
            // Claude can route a native permission prompt through this MCP
            // tool. Codex exec is non-interactive and does not emit approval
            // requests, so advertising the tool there would promise authority
            // it cannot apply to Codex's sandbox.
            ObjectNode permissionTool = bridgeTools.addObject()
                    .put("name", NewFlowAgentPermissions.TOOL_NAME)
                    .put("description", "Ask the user to approve a tool use"
                            + " that is not pre-approved.");
            permissionTool.putObject("inputSchema")
                    .put("type", "object")
                    .put("additionalProperties", true);
            bridgedExecutor = call ->
                    NewFlowAgentPermissions.TOOL_NAME.equals(call.name())
                            ? ToolExecutor.ToolCallResult.ok(
                                    permissions.ask(
                                            permissionOwnerId, call.input()))
                            : executor.execute(call);
        }
        String guidance = toolingGuidance(vendor, readOnly);
        String prompt = systemPrompt == null || systemPrompt.isBlank()
                ? guidance
                : systemPrompt + "\n\n" + guidance;
        try (NewFlowAgentToolBridge.Registration registration =
                     bridge.open(runId, bridgeTools, bridgedExecutor)) {
            String mcpUrl = "http://127.0.0.1:" + serverPort
                    + registration.path();
            Path mcpConfig = vendor == CliAgentArgv.Vendor.CLAUDE_CODE
                    ? writeMcpConfig(scratch, mcpUrl) : null;
            if (readOnly) {
                Map<String, String> scrubbed;
                try {
                    scrubbed = CliWriterContainment.environment(scratch);
                }
                catch (IOException failure) {
                    // The scrub is the only thing standing between a read-only
                    // agent and the user's own git credentials, so a turn that
                    // cannot have it does not run.
                    throw new UncheckedIOException(
                            "could not scrub the agent's environment", failure);
                }
                return execute(
                        runId, binding, prompt, vendor, directory,
                        scratch.resolve("pgid"), mcpConfig, mcpUrl, scrubbed,
                        true, allowedTools(tools), registration, journal,
                        interrupted);
            }
            CliWriterContainment.Applied contained;
            try {
                contained = CliWriterContainment.apply(
                        directory, scratch, "origin");
            }
            catch (IOException failure) {
                // Containment is not optional. A turn that cannot be contained
                // does not run uncontained; it does not run.
                throw new UncheckedIOException(
                        "could not contain the agent's worktree", failure);
            }
            catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while containing the agent's worktree",
                        interruption);
            }
            try {
                return execute(
                        runId, binding, prompt, vendor, directory,
                        scratch.resolve("pgid"), mcpConfig, mcpUrl,
                        contained.environment(), false, allowedTools(tools),
                        registration, journal, interrupted);
            }
            finally {
                // Lifted even when the turn threw: a crashed turn otherwise
                // leaves the refusing push URL behind and the program's own
                // publish would fail on it later, far from the cause.
                lift(directory, contained);
            }
        }
    }

    private static String toolingGuidance(
            CliAgentArgv.Vendor vendor, boolean readOnly)
    {
        String scope = readOnly
                ? " This is a read-only role: inspect and search without"
                        + " editing the worktree."
                : " You may read, search, edit, and run common builds or tests"
                        + " inside the supplied worktree.";
        String unavailable = vendor == CliAgentArgv.Vendor.CLAUDE_CODE
                ? " Sandboxed shell commands inside this worktree are"
                        + " automatically allowed. Operations outside that"
                        + " boundary stay unavailable; continue another way."
                : " Stay within the supplied sandbox; if a native operation is"
                        + " unavailable, continue another way.";
        return TOOLING_GUIDANCE + scope + unavailable;
    }

    private static void deleteRecursively(Path root)
    {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        }
                        catch (IOException ignored) {
                            // A leftover scratch directory is litter, not a
                            // correctness problem, and must not fail the turn.
                        }
                    });
        }
        catch (IOException ignored) {
            log.debug("could not clean the agent scratch directory {}", root);
        }
    }

    private static void lift(
            Path worktree, CliWriterContainment.Applied contained)
    {
        try {
            CliWriterContainment.lift(worktree, "origin", contained);
        }
        catch (IOException failure) {
            // Never masks the turn's own outcome: the program's later push is
            // what would fail, and it says so there.
            log.error("could not lift writer containment from {}", worktree,
                    failure);
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while lifting writer containment from {}",
                    worktree);
        }
    }

    private Outcome execute(
            String runId,
            NewFlowAgentLaunches.Binding binding,
            String systemPrompt,
            CliAgentArgv.Vendor vendor,
            Path directory,
            Path pgidFile,
            Path mcpConfig,
            String mcpUrl,
            Map<String, String> scrubbed,
            boolean readOnly,
            List<String> allowedTools,
            NewFlowAgentToolBridge.Registration registration,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        // Continue this role's own conversation where the last turn left it.
        // Read from the session row rather than held in memory, because the
        // case that matters is a restart — an id that only exists in this JVM
        // resumes nothing.
        String resume = runtime.resumableProviderSession(runId).orElse(null);
        FlowRuntime.ProviderUsageBaseline usageBaseline = resume == null
                ? FlowRuntime.ProviderUsageBaseline.ZERO
                : runtime.providerUsageBaseline(runId, resume);
        Outcome outcome = attempt(
                binding, systemPrompt, vendor, directory, pgidFile,
                mcpConfig, mcpUrl, scrubbed, readOnly, allowedTools, resume,
                usageBaseline, registration, journal, interrupted);
        if (resume != null && resumeFailedBeforeWork(outcome)) {
            // The CLI refused the stored session — a wiped vendor state or an
            // engine switch — and exited before opening a conversation, so
            // nothing ran and nothing was spent. Forget the dead handle and
            // run the turn once as a fresh conversation; keeping the id would
            // fail every later turn of this role the same way.
            log.warn("run {} could not resume provider session {};"
                    + " starting a fresh session", runId, resume);
            runtime.retireProviderSession(runId, resume);
            outcome = attempt(
                    binding, systemPrompt, vendor, directory, pgidFile,
                    mcpConfig, mcpUrl, scrubbed, readOnly, allowedTools, null,
                    FlowRuntime.ProviderUsageBaseline.ZERO,
                    registration, journal, interrupted);
        }
        // After burial, so a turn that could not be proven dead never adds to a
        // budget it may still be spending against. A cancelled or failed turn
        // still spent what it spent, so this is not conditional on the outcome.
        journal.usage(
                outcome.providerSessionId(),
                outcome.turn().tokensIn(),
                outcome.turn().tokensOut(),
                outcome.turn().costMilliUsd());
        return outcome;
    }

    /**
     * A resumed launch that died without opening a conversation or producing
     * a single assistant round did no work; only that shape is safe to rerun.
     */
    private static boolean resumeFailedBeforeWork(Outcome outcome)
    {
        return outcome.turn().end() == TurnResult.End.ABORTED
                && outcome.providerSessionId() == null
                && !outcome.providerWorkStarted();
    }

    private Outcome attempt(
            NewFlowAgentLaunches.Binding binding,
            String systemPrompt,
            CliAgentArgv.Vendor vendor,
            Path directory,
            Path pgidFile,
            Path mcpConfig,
            String mcpUrl,
            Map<String, String> scrubbed,
            boolean readOnly,
            List<String> allowedTools,
            String resumeSessionId,
            FlowRuntime.ProviderUsageBaseline usageBaseline,
            NewFlowAgentToolBridge.Registration registration,
            TurnJournal journal,
            BooleanSupplier interrupted)
    {
        List<String> argv = CliAgentArgv.of(new CliAgentArgv.Launch(
                vendor,
                binding.cliBinary(),
                binding.model(),
                binding.reasoningEffort(),
                directory,
                systemPrompt,
                readOnly,
                mcpConfig,
                mcpUrl,
                vendor == CliAgentArgv.Vendor.CLAUDE_CODE
                        ? "mcp__bytequay__" + NewFlowAgentPermissions.TOOL_NAME
                        : null,
                null,
                resumeSessionId,
                allowedTools,
                List.of(),
                true));
        Map<String, String> environment = new HashMap<>(scrubbed);
        // The bridge resolves run, capability, role and fence from the run id in
        // the URL. Nothing about the fence is put in the environment, where the
        // agent could read it and forge a call.
        environment.put("BYTEQUAY_NEW_FLOW_MCP_URL", mcpUrl);
        if (vendor == CliAgentArgv.Vendor.CLAUDE_CODE) {
            environment.put(
                    "MCP_TOOL_TIMEOUT", CLAUDE_MCP_TOOL_TIMEOUT_MILLIS);
        }

        ProcessGroup.Spawned spawned;
        try {
            spawned = ProcessGroup.start(
                    argv, directory, environment, pgidFile);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "could not launch the agent CLI", failure);
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while launching the agent CLI", interruption);
        }

        Transcript transcript = new Transcript(
                vendor == CliAgentArgv.Vendor.CLAUDE_CODE
                        ? new StreamJsonParser(mapper)
                        : new CodexJsonParser(mapper),
                usageBaseline,
                journal::activity);
        boolean stopped;
        int exit = -1;
        Thread reader;
        try {
            // Before a single byte of work reaches it. Everything after this
            // point is recoverable by a later JVM; everything before it is not.
            journal.group(
                    spawned.process().pid(),
                    spawned.pgid(),
                    spawned.leaderStartedAt());
            deliverPrompt(spawned.process(), vendor,
                    vendor == CliAgentArgv.Vendor.CODEX
                            ? systemPrompt + "\n\n" + USER_MESSAGE
                            : USER_MESSAGE);
            // Read on its own thread rather than inline. A background child the
            // agent leaves behind inherits stdout, so waiting for end-of-stream
            // would wait for that child — which is precisely the process this
            // turn exists to kill, and would hang the turn until it chose to
            // stop.
            reader = Thread.ofVirtual().start(
                    () -> transcript.read(spawned.process().getInputStream()));
            stopped = !awaitExit(
                    spawned.process(), registration, interrupted);
            if (!stopped) {
                exit = spawned.process().exitValue();
            }
        }
        finally {
            // Burial is what closes the pipe for any leftover holder, so it has
            // to happen before the reader can finish.
            buryOrFail(spawned.pgid());
        }
        join(reader);
        return transcript.outcome(exit, stopped);
    }

    /** Waits for the direct child, giving up when the turn is cancelled. */
    private boolean awaitExit(
            Process process,
            NewFlowAgentToolBridge.Registration registration,
            BooleanSupplier interrupted)
    {
        try {
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
                // HTTP arrives on a servlet thread, but every capability is
                // fenced to this exact owner thread. Pumping here preserves
                // that proof while the subprocess waits for its MCP response.
                registration.executePendingCalls();
                if (interrupted != null && interrupted.getAsBoolean()) {
                    return false;
                }
            }
            registration.executePendingCalls();
            return true;
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> allowedTools(ArrayNode tools)
    {
        List<String> allowed = new ArrayList<>();
        for (var tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "CLI tool manifest contains an unnamed tool");
            }
            allowed.add("mcp__bytequay__" + name);
        }
        return List.copyOf(allowed);
    }

    private static void join(Thread reader)
    {
        try {
            // The pipe is closed by now, so this is a formality; bounded anyway
            // so a stuck reader cannot hold the writer fence.
            reader.join(READER_JOIN_TIMEOUT);
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends the turn's work on stdin and closes it.
     *
     * <p>Not in argv: a reconstructed context is large, and an oversized command
     * line fails at {@code exec(2)} before the agent exists to report it.
     */
    private void deliverPrompt(
            Process process, CliAgentArgv.Vendor vendor, String prompt)
    {
        try (OutputStream stdin = process.getOutputStream()) {
            String input = prompt;
            if (vendor == CliAgentArgv.Vendor.CLAUDE_CODE) {
                ObjectNode line = mapper.createObjectNode();
                line.put("type", "user");
                ObjectNode message = line.putObject("message");
                message.put("role", "user");
                message.put("content", prompt);
                line.putNull("parent_tool_use_id");
                input = line + "\n";
            }
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
        catch (IOException closed) {
            // The agent exited before reading its prompt. That is the agent
            // failing, not the launch, and the drain below reports why.
            log.warn("the agent CLI closed stdin before the prompt was"
                    + " delivered", closed);
        }
    }

    /**
     * Accumulates one turn's stream off the reading thread.
     *
     * <p>Fields are written only by that thread and read only after it is
     * joined, which is why they need no synchronization beyond the join.
     */
    private static final class Transcript
    {
        private final CliStreamParser parser;
        private final Consumer<StreamEvent> activity;
        private String sessionId;
        private String finalText;
        private long tokensIn;
        private long tokensOut;
        private long costMilliUsd;
        private int rounds;
        private final long priorTokensIn;
        private final long priorTokensOut;
        private String failureText;
        private final StringBuilder diagnostic = new StringBuilder();
        private boolean providerWorkStarted;

        private Transcript(
                CliStreamParser parser,
                FlowRuntime.ProviderUsageBaseline usageBaseline,
                Consumer<StreamEvent> activity)
        {
            this.parser = parser;
            this.activity = requireNonNull(activity, "activity is null");
            priorTokensIn = usageBaseline.tokensIn();
            priorTokensOut = usageBaseline.tokensOut();
        }

        private void read(InputStream stdout)
        {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    List<StreamEvent> events = parser.parse(line, Instant.now());
                    if (events.isEmpty() && !line.isBlank()
                            && !line.stripLeading().startsWith("{")
                            && diagnostic.length() < DIAGNOSTIC_LIMIT) {
                        if (!diagnostic.isEmpty()) {
                            diagnostic.append('\n');
                        }
                        int remaining = DIAGNOSTIC_LIMIT - diagnostic.length();
                        if (remaining > 0) {
                            diagnostic.append(line.strip(), 0,
                                    Math.min(line.strip().length(), remaining));
                        }
                    }
                    for (StreamEvent event : events) {
                        activity.accept(event);
                        accept(event);
                    }
                }
            }
            catch (IOException broken) {
                // Expected when the group is buried under a running agent; the
                // turn's outcome comes from the exit status, not from this.
                log.debug("the agent CLI output stream ended", broken);
            }
        }

        private void accept(StreamEvent event)
        {
            if (event instanceof StreamEvent.SessionStarted started
                    && !started.sessionId().isBlank()) {
                sessionId = started.sessionId();
            }
            else if (event instanceof StreamEvent.AssistantText text) {
                finalText = text.text();
                rounds++;
                providerWorkStarted = true;
            }
            else if (event instanceof StreamEvent.TurnDone done) {
                providerWorkStarted = true;
                costMilliUsd += done.costUsdMilli();
                if (parser.reportsCumulativeUsage()) {
                    // Codex reports the session running total on every turn, so
                    // subtract the durable session baseline before recording
                    // this attempt's own share.
                    if (done.tokensIn() < priorTokensIn
                            || done.tokensOut() < priorTokensOut) {
                        tokensIn = 0;
                        tokensOut = 0;
                        failureText = "provider cumulative usage regressed"
                                + " below the frozen session baseline";
                    }
                    else {
                        tokensIn = done.tokensIn() - priorTokensIn;
                        tokensOut = done.tokensOut() - priorTokensOut;
                    }
                }
                else {
                    tokensIn += done.tokensIn();
                    tokensOut += done.tokensOut();
                }
            }
            else if (event instanceof StreamEvent.ErrorOccurred failed) {
                failureText = failed.message();
            }
            else if (event instanceof StreamEvent.ToolCallStarted
                    || event instanceof StreamEvent.ToolCallDone
                    || event instanceof StreamEvent.ThinkingStarted
                    || event instanceof StreamEvent.ThinkingDone) {
                providerWorkStarted = true;
            }
        }

        private Outcome outcome(int exit, boolean stopped)
        {
            // Claude reports the authoritative answer separately from the
            // assistant envelopes the stream is built from; prefer it.
            TurnResult.End end = stopped
                    ? TurnResult.End.INTERRUPTED
                    : exit == 0 && failureText == null
                            ? TurnResult.End.COMPLETED : TurnResult.End.ABORTED;
            String text = end == TurnResult.End.ABORTED
                    && failureText != null
                    ? failureText
                    : end == TurnResult.End.ABORTED && !diagnostic.isEmpty()
                            ? diagnostic.toString()
                            : parser.terminalResult().orElse(finalText);
            return new Outcome(
                    new TurnResult(
                            text == null ? "" : text,
                            tokensIn,
                            tokensOut,
                            costMilliUsd,
                            rounds,
                            end),
                    sessionId,
                    providerWorkStarted);
        }
    }

    /**
     * The receipt this whole transport exists for. Nothing below the caller may
     * treat a turn as finished until the group is gone, so a group that outlives
     * its kill fails the turn rather than returning a result beside it.
     */
    private void buryOrFail(long pgid)
    {
        Optional<Long> alive;
        try {
            alive = ProcessGroup.bury(pgid);
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted before the agent process group " + pgid
                            + " could be proven gone", interruption);
        }
        if (alive.isPresent()) {
            throw new IllegalStateException(
                    "the agent process group " + pgid + " outlived its kill;"
                            + " the worktree cannot be handed on");
        }
    }

    private Path writeMcpConfig(Path scratch, String mcpUrl)
    {
        try {
            Files.createDirectories(scratch);
            Path config = scratch.resolve("mcp.json");
            Files.writeString(
                    config,
                    mapper.writeValueAsString(mapper.createObjectNode().set(
                            "mcpServers",
                            mapper.createObjectNode().set(
                                    "bytequay",
                                    mapper.createObjectNode()
                                            .put("type", "http")
                                            .put("url", mcpUrl)))),
                    StandardCharsets.UTF_8);
            return config;
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "could not write the agent's MCP config", failure);
        }
    }

    private static CliAgentArgv.Vendor vendor(
            NewFlowAgentLaunches.Binding binding)
    {
        return switch (binding.providerName()) {
            case "claude-code" -> CliAgentArgv.Vendor.CLAUDE_CODE;
            case "codex" -> CliAgentArgv.Vendor.CODEX;
            default -> throw new NewFlowAgentLaunches.LaunchUnavailableException(
                    "unsupported CLI agent for a run: "
                            + binding.providerName());
        };
    }
}
