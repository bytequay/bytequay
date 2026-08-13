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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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

    private final NewFlowAgentToolBridge bridge;
    private final ObjectMapper mapper;
    private final int serverPort;

    NewFlowCliTurn(
            NewFlowAgentToolBridge bridge,
            ObjectMapper mapper,
            int serverPort)
    {
        this.bridge = requireNonNull(bridge, "bridge is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.serverPort = serverPort;
    }

    /**
     * Told the group a turn launched, before that turn is given any work.
     *
     * <p>A callback rather than a runtime handle because the claim, fence and
     * attempt this has to be written against belong to the supervisor, and the
     * body is handed only a capability.
     */
    @FunctionalInterface
    interface GroupRecorder
    {
        void record(long agentPid, long agentPgid, Instant agentStartedAt);
    }

    /**
     * @param providerSessionId the vendor's own session id, for resuming this
     *         role's next turn; null when the CLI never reported one
     */
    record Outcome(TurnResult turn, String providerSessionId) {}

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
            GroupRecorder recorder,
            BooleanSupplier interrupted)
    {
        requireNonNull(worktree, "worktree is null");
        Path scratch = worktree.resolve(".git").resolve("bytequay-cli");
        return run(
                runId, binding, tools, systemPrompt, executor, worktree,
                scratch, false, recorder, interrupted);
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
            GroupRecorder recorder,
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
                    runId, binding, tools, systemPrompt, executor, scratch,
                    scratch, true, recorder, interrupted);
        }
        finally {
            deleteRecursively(scratch);
        }
    }

    private Outcome run(
            String runId,
            NewFlowAgentLaunches.Binding binding,
            ArrayNode tools,
            String systemPrompt,
            ToolExecutor executor,
            Path directory,
            Path scratch,
            boolean readOnly,
            GroupRecorder recorder,
            BooleanSupplier interrupted)
    {
        requireNonNull(binding, "binding is null");
        if (binding.isApi()) {
            throw new IllegalArgumentException(
                    "an API binding must not take the CLI turn path");
        }
        requireNonNull(tools, "tools is null");
        requireNonNull(executor, "executor is null");
        requireNonNull(recorder, "recorder is null");

        CliAgentArgv.Vendor vendor = vendor(binding);
        try (NewFlowAgentToolBridge.Registration registration =
                     bridge.open(runId, tools, executor)) {
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
                        binding, systemPrompt, vendor, directory,
                        scratch.resolve("pgid"), mcpConfig, mcpUrl, scrubbed,
                        true, recorder, interrupted);
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
                        binding, systemPrompt, vendor, directory,
                        scratch.resolve("pgid"), mcpConfig, mcpUrl,
                        contained.environment(), false, recorder, interrupted);
            }
            finally {
                // Lifted even when the turn threw: a crashed turn otherwise
                // leaves the refusing push URL behind and the program's own
                // publish would fail on it later, far from the cause.
                lift(directory, contained);
            }
        }
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
            NewFlowAgentLaunches.Binding binding,
            String systemPrompt,
            CliAgentArgv.Vendor vendor,
            Path directory,
            Path pgidFile,
            Path mcpConfig,
            String mcpUrl,
            Map<String, String> scrubbed,
            boolean readOnly,
            GroupRecorder recorder,
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
                null,
                null,
                null,
                List.of(),
                List.of()));
        Map<String, String> environment = new HashMap<>(scrubbed);
        // The bridge resolves run, capability, role and fence from the run id in
        // the URL. Nothing about the fence is put in the environment, where the
        // agent could read it and forge a call.
        environment.put("BYTEQUAY_NEW_FLOW_MCP_URL", mcpUrl);

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
                        : new CodexJsonParser(mapper));
        boolean stopped;
        int exit = -1;
        Thread reader;
        try {
            // Before a single byte of work reaches it. Everything after this
            // point is recoverable by a later JVM; everything before it is not.
            recorder.record(
                    spawned.process().pid(),
                    spawned.pgid(),
                    spawned.leaderStartedAt());
            deliverPrompt(spawned.process());
            // Read on its own thread rather than inline. A background child the
            // agent leaves behind inherits stdout, so waiting for end-of-stream
            // would wait for that child — which is precisely the process this
            // turn exists to kill, and would hang the turn until it chose to
            // stop.
            reader = Thread.ofVirtual().start(
                    () -> transcript.read(spawned.process().getInputStream()));
            stopped = !awaitExit(spawned.process(), interrupted);
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
    private boolean awaitExit(Process process, BooleanSupplier interrupted)
    {
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (interrupted != null && interrupted.getAsBoolean()) {
                    return false;
                }
            }
            return true;
        }
        catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        }
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
    private void deliverPrompt(Process process)
    {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(USER_MESSAGE.getBytes(StandardCharsets.UTF_8));
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
        private String sessionId;
        private String finalText;
        private long tokensIn;
        private long tokensOut;
        private long costMilliUsd;
        private int rounds;

        private Transcript(CliStreamParser parser)
        {
            this.parser = parser;
        }

        private void read(InputStream stdout)
        {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    for (StreamEvent event : parser.parse(line, Instant.now())) {
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
            }
            else if (event instanceof StreamEvent.TurnDone done) {
                costMilliUsd += done.costUsdMilli();
                if (parser.reportsCumulativeUsage()) {
                    // Codex reports the session running total on every turn, so
                    // summing them would grow quadratically.
                    tokensIn = done.tokensIn();
                    tokensOut = done.tokensOut();
                }
                else {
                    tokensIn += done.tokensIn();
                    tokensOut += done.tokensOut();
                }
            }
        }

        private Outcome outcome(int exit, boolean stopped)
        {
            // Claude reports the authoritative answer separately from the
            // assistant envelopes the stream is built from; prefer it.
            String text = parser.terminalResult().orElse(finalText);
            TurnResult.End end = stopped
                    ? TurnResult.End.INTERRUPTED
                    : exit == 0
                            ? TurnResult.End.COMPLETED : TurnResult.End.ABORTED;
            return new Outcome(
                    new TurnResult(
                            text == null ? "" : text,
                            tokensIn,
                            tokensOut,
                            costMilliUsd,
                            rounds,
                            end),
                    sessionId);
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
