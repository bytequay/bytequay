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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.agents.cli.CliAgentArgv;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.StreamJsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static java.util.Objects.requireNonNull;

/** Fresh-process-per-attempt CLI adapter using the existing provider stream parsers. */
public final class CliAgentTurnProviderSession
        implements AgentTurnProviderSession
{
    private static final String CLAUDE_REVIEW_TOOLS =
            "mcp__bytequay__record_assignment,mcp__bytequay__record_hypothesis,"
            + "mcp__bytequay__record_step,mcp__bytequay__read_diff,"
            + "mcp__bytequay__read_file,mcp__bytequay__search_diff,"
            + "mcp__bytequay__record_evidence,mcp__bytequay__record_finding,"
            + "mcp__bytequay__record_verification";

    private final ObjectMapper mapper;
    private final Function<CliProvider, String> binary;

    public CliAgentTurnProviderSession(ObjectMapper mapper)
    {
        this(mapper, provider -> switch (provider) {
            case CODEX -> "codex";
            case CLAUDE_CODE -> "claude";
        });
    }

    CliAgentTurnProviderSession(
            ObjectMapper mapper, Function<CliProvider, String> binary)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.binary = requireNonNull(binary, "binary is null");
    }

    @Override
    public Session open(Request request, Observer observer)
    {
        requireNonNull(request, "request is null");
        requireNonNull(observer, "observer is null");
        if (request.transport() != CLI) {
            throw new IllegalArgumentException(
                    "CLI adapter cannot run " + request.transport() + " transport");
        }
        CliProvider provider = CliProvider.fromId(request.provider());
        String executable = requireNonNull(
                binary.apply(provider), "resolved binary is null");
        if (executable.isBlank()) {
            throw new IllegalStateException("resolved binary is blank");
        }
        return new CliSession(request, provider, executable, observer, mapper);
    }

    /**
     * Maps this flow's request onto the shared launch. The vendor flags live in
     * {@link CliAgentArgv}; what stays here is this flow's own policy — which
     * tool profile earns which allowlist — because that is the part the
     * greenfield runtime does not share.
     */
    static List<String> buildArgv(
            Request request,
            CliProvider provider,
            String executable,
            Path mcpConfig)
    {
        requireNonNull(request, "request is null");
        ImmutableList.Builder<String> allowed = ImmutableList.builder();
        if (!request.preapprovedMcpTools().isEmpty()) {
            allowed.add(request.preapprovedMcpTools().stream()
                    .sorted()
                    .map(tool -> "mcp__" + request.toolEndpoint().serverName()
                            + "__" + tool)
                    .collect(Collectors.joining(",")));
        }
        if (request.toolEndpoint().profile()
                == ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY) {
            allowed.add(CLAUDE_REVIEW_TOOLS);
        }
        return CliAgentArgv.of(new CliAgentArgv.Launch(
                provider == CliProvider.CLAUDE_CODE
                        ? CliAgentArgv.Vendor.CLAUDE_CODE
                        : CliAgentArgv.Vendor.CODEX,
                executable,
                request.model(),
                request.reasoningEffort(),
                request.workingDirectory(),
                request.systemPrompt(),
                request.access() == READ_ONLY,
                mcpConfig,
                request.toolEndpoint().url(),
                request.permissionPromptTool(),
                request.maxCostUsdMilli(),
                request.resumeSessionId(),
                allowed.build(),
                request.images().stream()
                        .map(AgentTurnProviderSession.ImageAttachment::path)
                        .toList()));
    }

    private static String composePrompt(Request request)
    {
        return request.systemPrompt() == null
                ? request.prompt()
                : request.systemPrompt() + "\n\n" + request.prompt();
    }

    private static void deliverPrompt(
            Process process, CliProvider provider, Request request)
            throws IOException
    {
        try (OutputStream stdin = process.getOutputStream()) {
            if (provider == CliProvider.CLAUDE_CODE) {
                stdin.write(providerPrompt(request, provider)
                        .getBytes(StandardCharsets.UTF_8));
            }
            else {
                stdin.write(composePrompt(request)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static String providerPrompt(Request request, CliProvider provider)
    {
        if (provider != CliProvider.CLAUDE_CODE || request.images().isEmpty()) {
            return request.prompt();
        }
        String label = request.images().size() == 1
                ? "Attached image (read this managed file):"
                : "Attached images (read these managed files):";
        return request.prompt() + "\n\n" + label + "\n- "
                + String.join("\n- ", request.images().stream()
                        .map(AgentTurnProviderSession.ImageAttachment::path)
                        .toList());
    }

    private static final class CliSession
            implements Session
    {
        private final Request request;
        private final CliProvider provider;
        private final String executable;
        private final Observer observer;
        private final ObjectMapper mapper;
        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong logSequence = new AtomicLong();

        private CliSession(
                Request request,
                CliProvider provider,
                String executable,
                Observer observer,
                ObjectMapper mapper)
        {
            this.request = requireNonNull(request, "request is null");
            this.provider = requireNonNull(provider, "provider is null");
            this.executable = requireNonNull(executable, "executable is null");
            this.observer = requireNonNull(observer, "observer is null");
            this.mapper = requireNonNull(mapper, "mapper is null");
        }

        @Override
        public Result startAndAwait(WriterFence writerFence)
                throws Exception
        {
            requireFence(request, writerFence);
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("provider session was already started");
            }
            if (closed.get()) {
                throw new IllegalStateException("provider session is closed");
            }
            if (canceled.get()) {
                return canceledResult(null, 0, 0, 0, null);
            }
            request.images().forEach(
                    AgentTurnProviderSession.ImageAttachment::readVerified);

            Path mcpConfig = provider == CliProvider.CLAUDE_CODE
                    ? createMcpConfig(request.toolEndpoint(), mapper)
                    : null;
            try {
                boolean mayFallback = request.resumeSessionId() != null
                        && request.fallbackPrompt() != null;
                Attempt attempt = runAttempt(
                        request, writerFence, mcpConfig, mayFallback);
                if (!attempt.sessionUnavailable()) {
                    return attempt.result();
                }
                if (canceled.get()) {
                    return canceledResult(null, 0, 0, 0, null);
                }
                return runAttempt(
                        freshFallbackRequest(), writerFence, mcpConfig, false)
                        .result();
            }
            finally {
                deleteMcpConfig(mcpConfig);
            }
        }

        private Attempt runAttempt(
                Request attemptRequest,
                WriterFence writerFence,
                Path mcpConfig,
                boolean mayFallback)
                throws Exception
        {
            if (canceled.get()) {
                return new Attempt(
                        canceledResult(null, 0, 0, 0, null), false);
            }
            Process launched = startProcess(
                    attemptRequest, writerFence, mcpConfig);
            trackProcess(launched);
            if (canceled.get()) {
                stopProcessTree(launched);
                return new Attempt(canceledResult(
                        null, 0, 0, 0, launched.pid()), false);
            }

            try {
                deliverPrompt(launched, provider, attemptRequest);
            }
            catch (IOException e) {
                stopProcessTree(launched);
                if (canceled.get()) {
                    return new Attempt(canceledResult(
                            null, 0, 0, 0, launched.pid()), false);
                }
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "provider started but prompt delivery failed", e);
            }
            CliStreamParser parser = switch (provider) {
                case CODEX -> new CodexJsonParser(mapper);
                case CLAUDE_CODE -> new StreamJsonParser(mapper);
            };

            StringBuilder finalText = new StringBuilder();
            StringBuilder diagnostic = new StringBuilder();
            String sessionId = null;
            String error = null;
            long inputTokens = 0;
            long outputTokens = 0;
            long costUsdMilli = 0;
            Long cumulativeInputTokens = null;
            Long cumulativeOutputTokens = null;
            String cumulativeUsageError = null;
            boolean providerWorkStarted = false;
            boolean ambiguousOutput = false;
            boolean turnDone = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    observer.log(logSequence.getAndIncrement(), logPayload(line));
                    List<StreamEvent> events = parser.parse(line, Instant.now());
                    boolean jsonLine = line.stripLeading().startsWith("{");
                    if (events.isEmpty()
                            && !line.isBlank()
                            && !isBenignPreamble(provider, line)
                            && (jsonLine
                                    || !isSessionUnavailable(provider, line))) {
                        // Both stream parsers deliberately ignore unknown wire
                        // shapes. During resume, ignored output is ambiguous:
                        // it may represent accepted work and therefore must
                        // disable automatic replay.
                        ambiguousOutput = true;
                    }
                    if (!jsonLine) {
                        // Provider CLIs may report resume lookup failures on
                        // stderr as plain text. JSON error envelopes are
                        // classified through their parsed error message.
                        appendDiagnostic(diagnostic, line);
                    }
                    for (StreamEvent event : events) {
                        if (isProviderWorkEvidence(event)) {
                            providerWorkStarted = true;
                        }
                        if (event instanceof StreamEvent.SessionStarted started
                                && !started.sessionId().isBlank()) {
                            sessionId = started.sessionId();
                            observer.providerSession(provider.id(), sessionId);
                        }
                        else if (event instanceof StreamEvent.AssistantText assistant) {
                            if (provider == CliProvider.CODEX) {
                                // Codex emits progress as completed agent_message
                                // items before its authoritative final message.
                                finalText.setLength(0);
                                finalText.append(assistant.text().strip());
                            }
                            // Claude's owner result comes only from its terminal
                            // result frame, applied below. Its per-tool-round
                            // frames stay log-only evidence: concatenating them
                            // let an aborted run (no terminal result) present
                            // narration as the turn's answer.
                        }
                        else if (event instanceof StreamEvent.TurnDone done) {
                            turnDone = true;
                            if (parser.reportsCumulativeUsage()) {
                                cumulativeInputTokens = done.tokensIn();
                                cumulativeOutputTokens = done.tokensOut();
                                if (done.tokensIn()
                                        < attemptRequest.priorCumulativeInputTokens()
                                        || done.tokensOut()
                                        < attemptRequest.priorCumulativeOutputTokens()) {
                                    inputTokens = 0;
                                    outputTokens = 0;
                                    cumulativeUsageError =
                                            "provider cumulative usage regressed "
                                            + "below the frozen session baseline";
                                }
                                else {
                                    inputTokens = done.tokensIn()
                                            - attemptRequest.priorCumulativeInputTokens();
                                    outputTokens = done.tokensOut()
                                            - attemptRequest.priorCumulativeOutputTokens();
                                }
                            }
                            else {
                                inputTokens = done.tokensIn();
                                outputTokens = done.tokensOut();
                            }
                            costUsdMilli = done.costUsdMilli();
                        }
                        else if (event instanceof StreamEvent.ErrorOccurred failed) {
                            error = failed.message();
                        }
                        else if (event instanceof StreamEvent.SessionEnded ended
                                && ended.errorMessage() != null
                                && !ended.errorMessage().isBlank()) {
                            error = ended.errorMessage();
                        }
                    }
                }
            }
            catch (IOException e) {
                if (canceled.get() && !turnDone) {
                    return new Attempt(canceledResult(
                            sessionId, inputTokens, outputTokens,
                            costUsdMilli, launched.pid()), false);
                }
                if (!canceled.get()) {
                    throw new ExecutionPorts.IndeterminateExecutionException(
                            "provider output ended ambiguously", e);
                }
                // A terminal provider frame is stronger evidence than a
                // concurrent cancel that closes the output pipe.
            }

            parser.terminalResult().ifPresent(authoritative -> {
                finalText.setLength(0);
                finalText.append(authoritative.strip());
            });

            int exit;
            try {
                exit = launched.waitFor();
            }
            catch (InterruptedException e) {
                cancel();
                Thread.currentThread().interrupt();
                if (turnDone) {
                    return new Attempt(completedResult(
                            sessionId, finalText, inputTokens, outputTokens,
                            costUsdMilli, launched.pid(),
                            cumulativeUsageError == null
                                    ? error : cumulativeUsageError,
                            cumulativeInputTokens,
                            cumulativeOutputTokens), false);
                }
                return new Attempt(canceledResult(
                        sessionId, inputTokens, outputTokens,
                        costUsdMilli, launched.pid()), false);
            }
            if (canceled.get() && !turnDone) {
                return new Attempt(canceledResult(
                        sessionId, inputTokens, outputTokens,
                        costUsdMilli, launched.pid()), false);
            }
            if (exit != 0 && error == null && !turnDone) {
                error = provider.id() + " exited with code " + exit;
            }
            if (!turnDone && error == null) {
                error = "provider exited without terminal Turn evidence";
            }
            if (cumulativeUsageError != null) {
                error = cumulativeUsageError;
            }
            boolean sessionUnavailable = mayFallback
                    && !providerWorkStarted
                    && !ambiguousOutput
                    && isSessionUnavailable(
                            provider, error + "\n" + diagnostic);
            if (sessionUnavailable) {
                return new Attempt(completedResult(
                        sessionId, finalText, inputTokens, outputTokens,
                        costUsdMilli, launched.pid(), error,
                        cumulativeInputTokens,
                        cumulativeOutputTokens), true);
            }
            if (error == null
                    && attemptRequest.maxCostUsdMilli() != null
                    && costUsdMilli >= attemptRequest.maxCostUsdMilli()) {
                error = "provider turn budget was exhausted";
            }
            return new Attempt(completedResult(
                    sessionId, finalText, inputTokens, outputTokens,
                    costUsdMilli, launched.pid(), error,
                    cumulativeInputTokens,
                    cumulativeOutputTokens), false);
        }

        private Request freshFallbackRequest()
        {
            return new Request(
                    request.transport(), request.provider(),
                    request.credentialAccount(), request.model(),
                    request.reasoningEffort(), request.workingDirectory(),
                    request.systemPrompt(), request.fallbackPrompt(),
                    request.images(), request.toolEndpoint(),
                    request.permissionPromptTool(), request.access(),
                    request.maxCostUsdMilli(), null, null, 0, 0,
                    request.preapprovedMcpTools());
        }

        private void announceProcess(Process launched)
        {
            observer.processStarted(
                    launched.pid(), "agent-turn/" + provider.id());
        }

        private void trackProcess(Process launched)
        {
            process.set(launched);
            try {
                announceProcess(launched);
            }
            catch (RuntimeException | Error registrationFailure) {
                try {
                    stopProcessTree(launched);
                }
                catch (RuntimeException | Error stopFailure) {
                    registrationFailure.addSuppressed(stopFailure);
                }
                process.compareAndSet(launched, null);
                throw registrationFailure;
            }
        }

        private Process startProcess(
                Request attemptRequest,
                WriterFence writerFence,
                Path mcpConfig)
                throws ExecutionPorts.RetryableExecutionException
        {
            ProcessBuilder builder = new ProcessBuilder(
                    buildArgv(attemptRequest, provider, executable, mcpConfig));
            builder.directory(attemptRequest.workingDirectory().toFile());
            // One stream lets the admitted dispatcher worker drain the process
            // without creating a second executor or pipe-drain thread.
            builder.redirectErrorStream(true);
            if (writerFence != null) {
                builder.environment().put(
                        "BYTEQUAY_WRITER_FENCING_TOKEN",
                        Long.toString(writerFence.fencingToken()));
                builder.environment().put(
                        "BYTEQUAY_WRITER_OPERATION_ID", writerFence.operationId());
                builder.environment().put(
                        "BYTEQUAY_WRITER_TASK_ID", writerFence.taskId());
                builder.environment().put(
                        "BYTEQUAY_WRITER_TASK_EPOCH",
                        Long.toString(writerFence.taskEpoch()));
            }
            if (provider == CliProvider.CLAUDE_CODE) {
                builder.environment().merge(
                        "NODE_OPTIONS",
                        "--max-old-space-size=512",
                        (existing, ours) -> existing.contains("--max-old-space-size")
                                ? existing : existing + " " + ours);
                builder.environment().remove("CLAUDE_CODE_SAFE_MODE");
                builder.environment().remove("CLAUDE_CODE_SIMPLE");
            }
            try {
                return builder.start();
            }
            catch (IOException e) {
                throw new ExecutionPorts.RetryableExecutionException(
                        "could not start " + executable + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void cancel()
        {
            canceled.set(true);
            stopProcessTree(process.get());
        }

        @Override
        public void close()
        {
            if (closed.compareAndSet(false, true)) {
                stopProcessTree(process.get());
            }
        }

        private Result canceledResult(
                String sessionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli,
                Long processPid)
        {
            return new Result(
                    Completion.CANCELED,
                    sessionId,
                    "",
                    inputTokens,
                    outputTokens,
                    costUsdMilli,
                    processPid,
                    "provider session canceled");
        }

        private static Result completedResult(
                String sessionId,
                StringBuilder finalText,
                long inputTokens,
                long outputTokens,
                long costUsdMilli,
                long processPid,
                String error,
                Long cumulativeInputTokens,
                Long cumulativeOutputTokens)
        {
            return new Result(
                    error == null ? Completion.SUCCEEDED : Completion.FAILED,
                    sessionId,
                    finalText.toString(),
                    inputTokens,
                    outputTokens,
                    costUsdMilli,
                    processPid,
                    error,
                    cumulativeInputTokens,
                    cumulativeOutputTokens);
        }

        private static void requireFence(Request request, WriterFence fence)
        {
            if (request.access() == READ_ONLY) {
                if (fence != null) {
                    throw new IllegalArgumentException(
                            "read-only provider session must not receive a writer fence");
                }
                return;
            }
            requireNonNull(fence, "worktree-writing provider session needs a writer fence");
            if (!request.workingDirectory().equals(Path.of(fence.worktreePath()))) {
                throw new IllegalArgumentException(
                        "writer fence worktree differs from provider working directory");
            }
        }

        private String logPayload(String line)
        {
            return mapper.createObjectNode()
                    .put("stream", "provider")
                    .put("line", line)
                    .toString();
        }
    }

    private record Attempt(Result result, boolean sessionUnavailable) {}

    private static boolean isProviderWorkEvidence(StreamEvent event)
    {
        if (event instanceof StreamEvent.SessionStarted started) {
            return started.sessionId() != null && !started.sessionId().isBlank();
        }
        if (event instanceof StreamEvent.UsageUpdated usage) {
            return usage.tokensIn() > 0 || usage.tokensOut() > 0;
        }
        if (event instanceof StreamEvent.TurnDone done) {
            return done.tokensIn() > 0 || done.tokensOut() > 0
                    || done.costUsdMilli() > 0;
        }
        return !(event instanceof StreamEvent.ErrorOccurred)
                && !(event instanceof StreamEvent.SessionEnded);
    }

    private static void appendDiagnostic(StringBuilder diagnostic, String line)
    {
        int remaining = 16_384 - diagnostic.length();
        if (remaining <= 0) {
            return;
        }
        if (diagnostic.length() > 0) {
            diagnostic.append('\n');
            remaining--;
        }
        diagnostic.append(line, 0, Math.min(remaining, line.length()));
    }

    static boolean isSessionUnavailable(
            CliProvider provider, String diagnostic)
    {
        requireNonNull(provider, "provider is null");
        if (diagnostic == null || diagnostic.isBlank()) {
            return false;
        }
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        return switch (provider) {
            case CODEX -> lower.contains("no rollout found for thread id");
            case CLAUDE_CODE ->
                    lower.contains("no conversation found with session id")
                            || lower.contains("conversation has expired")
                            || lower.contains("session has expired");
        };
    }

    private static boolean isBenignPreamble(
            CliProvider provider, String line)
    {
        if (provider != CliProvider.CODEX) {
            return false;
        }
        String text = line.strip();
        return text.equals("Reading prompt from stdin...")
                || text.equals("Reading additional input from stdin...");
    }

    static void stopProcessTree(Process process)
    {
        if (process == null) {
            return;
        }
        try {
            List<ProcessHandle> descendants = process.descendants().toList();
            for (int index = descendants.size() - 1; index >= 0; index--) {
                descendants.get(index).destroyForcibly();
            }
        }
        catch (RuntimeException ignored) {
            // Some restricted macOS processes deny descendant discovery. The
            // parent still has to be stopped; its own teardown normally
            // closes the process group and provider pipes.
        }
        process.toHandle().destroyForcibly();
    }

    static Path createMcpConfig(
            OwnerToolEndpoint endpoint, ObjectMapper mapper)
            throws ExecutionPorts.RetryableExecutionException
    {
        Path config = null;
        try {
            config = Files.createTempFile("bytequay-turn-mcp-", ".json");
            String json = mapper.writeValueAsString(
                    mapper.createObjectNode().set(
                            "mcpServers",
                            mapper.createObjectNode().set(
                                    endpoint.serverName(),
                                    mapper.createObjectNode()
                                            .put("type", "http")
                                            .put("url", endpoint.url()))));
            Files.writeString(config, json, StandardCharsets.UTF_8);
            config.toFile().deleteOnExit();
            return config;
        }
        catch (IOException e) {
            deleteMcpConfig(config);
            throw new ExecutionPorts.RetryableExecutionException(
                    "could not create scoped Claude MCP config", e);
        }
    }

    static void deleteMcpConfig(Path config)
    {
        if (config == null) {
            return;
        }
        try {
            Files.deleteIfExists(config);
        }
        catch (IOException ignored) {
            // deleteOnExit remains the crash-safe fallback.
        }
    }

    enum CliProvider
    {
        CODEX("codex"),
        CLAUDE_CODE("claude-code");

        private final String id;

        CliProvider(String id)
        {
            this.id = id;
        }

        private String id()
        {
            return id;
        }

        private static CliProvider fromId(String id)
        {
            requireNonNull(id, "provider is null");
            for (CliProvider provider : values()) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
            throw new IllegalArgumentException(
                    "unsupported CLI Agent Turn provider: " + id);
        }
    }
}
