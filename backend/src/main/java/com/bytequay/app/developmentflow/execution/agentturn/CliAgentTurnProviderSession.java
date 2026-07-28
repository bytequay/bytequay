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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static java.util.Objects.requireNonNull;

/** One-process-per-Turn CLI adapter using the existing provider stream parsers. */
public final class CliAgentTurnProviderSession
        implements AgentTurnProviderSession
{
    private static final String CLAUDE_ISOLATED_SETTINGS =
            "{\"autoMemoryEnabled\":false,\"attribution\":{\"commit\":\"\"}}";
    private static final String CLAUDE_READ_ONLY_TOOLS =
            "Read,Glob,Grep,WebFetch,WebSearch";

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

    static List<String> buildArgv(
            Request request,
            CliProvider provider,
            String executable,
            Path mcpConfig)
    {
        requireNonNull(request, "request is null");
        if (provider == CliProvider.CLAUDE_CODE) {
            ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                    .add(executable)
                    .add("-p")
                    .add("--output-format", "stream-json")
                    .add("--verbose")
                    .add("--setting-sources", "")
                    .add("--disable-slash-commands")
                    .add("--no-chrome")
                    .add("--settings", CLAUDE_ISOLATED_SETTINGS)
                    .add("--include-partial-messages")
                    .add("--mcp-config", requireNonNull(
                            mcpConfig, "Claude MCP config is null").toString())
                    .add("--strict-mcp-config")
                    .add("--permission-prompt-tool",
                            request.toolEndpoint().approvalPromptTool())
                    .add("--model", request.model());
            if (request.reasoningEffort() != null) {
                argv.add("--effort", request.reasoningEffort());
            }
            if (request.access() == READ_ONLY) {
                argv.add("--tools", CLAUDE_READ_ONLY_TOOLS);
            }
            if (request.systemPrompt() != null) {
                argv.add("--append-system-prompt", request.systemPrompt());
            }
            return argv.build();
        }

        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(executable)
                // Replace the complete MCP table. Combined with
                // --ignore-user-config this exposes one owner-scoped server,
                // never an inherited personal MCP catalog.
                .add("-c", "mcp_servers={bytequay={url=\""
                        + request.toolEndpoint().url()
                        + "\",default_tools_approval_mode=\"approve\"}}")
                .add("-c", "experimental_use_rmcp_client=true")
                .add("-c", "project_doc_max_bytes=0");
        if (request.reasoningEffort() != null) {
            argv.add("-c", "model_reasoning_effort=\""
                    + request.reasoningEffort() + "\"");
        }
        argv.add("exec")
                .add("--ignore-user-config")
                .add("--json")
                .add("--skip-git-repo-check")
                .add("--sandbox", request.access() == READ_ONLY
                        ? "read-only" : "workspace-write")
                .add("-C", request.workingDirectory().toString())
                .add("-m", request.model())
                .add(composePrompt(request));
        return argv.build();
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
                stdin.write(request.prompt().getBytes(StandardCharsets.UTF_8));
            }
        }
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

            Path mcpConfig = provider == CliProvider.CLAUDE_CODE
                    ? createMcpConfig(request.toolEndpoint(), mapper)
                    : null;
            try {
                if (canceled.get()) {
                    return canceledResult(null, 0, 0, 0, null);
                }
                Process launched = startProcess(writerFence, mcpConfig);
                process.set(launched);
                if (canceled.get()) {
                    stopProcessTree(launched);
                    return canceledResult(null, 0, 0, 0, launched.pid());
                }
                observer.processStarted(
                        launched.pid(), "agent-turn/" + provider.id());
                try {
                    deliverPrompt(launched, provider, request);
                }
                catch (IOException e) {
                    stopProcessTree(launched);
                    if (canceled.get()) {
                        return canceledResult(null, 0, 0, 0, launched.pid());
                    }
                    throw new ExecutionPorts.IndeterminateExecutionException(
                            "provider started but prompt delivery failed", e);
                }
                CliStreamParser parser = switch (provider) {
                    case CODEX -> new CodexJsonParser(mapper);
                    case CLAUDE_CODE -> new StreamJsonParser(mapper);
                };

                StringBuilder finalText = new StringBuilder();
                String sessionId = null;
                String error = null;
                long inputTokens = 0;
                long outputTokens = 0;
                long costUsdMilli = 0;
                boolean turnDone = false;
                long sequence = 0;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        launched.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        observer.log(sequence++, logPayload(line));
                        for (StreamEvent event : parser.parse(line, Instant.now())) {
                            if (event instanceof StreamEvent.SessionStarted started
                                    && !started.sessionId().isBlank()) {
                                sessionId = started.sessionId();
                                observer.providerSession(provider.id(), sessionId);
                            }
                            else if (event instanceof StreamEvent.AssistantText assistant
                                    && !assistant.text().isBlank()) {
                                if (finalText.length() > 0) {
                                    finalText.append("\n\n");
                                }
                                finalText.append(assistant.text().strip());
                            }
                            else if (event instanceof StreamEvent.TurnDone done) {
                                turnDone = true;
                                inputTokens = done.tokensIn();
                                outputTokens = done.tokensOut();
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
                        return canceledResult(
                                sessionId, inputTokens, outputTokens,
                                costUsdMilli, launched.pid());
                    }
                    if (!canceled.get()) {
                        throw new ExecutionPorts.IndeterminateExecutionException(
                                "provider output ended ambiguously", e);
                    }
                    // A terminal provider frame is stronger evidence than a
                    // concurrent cancel that closes the output pipe.
                }

                int exit;
                try {
                    exit = launched.waitFor();
                }
                catch (InterruptedException e) {
                    cancel();
                    Thread.currentThread().interrupt();
                    if (turnDone) {
                        return completedResult(
                                sessionId, finalText, inputTokens, outputTokens,
                                costUsdMilli, launched.pid(), error);
                    }
                    return canceledResult(
                            sessionId, inputTokens, outputTokens,
                            costUsdMilli, launched.pid());
                }
                if (canceled.get() && !turnDone) {
                    return canceledResult(
                            sessionId, inputTokens, outputTokens,
                            costUsdMilli, launched.pid());
                }
                if (exit != 0 && error == null && !turnDone) {
                    error = provider.id() + " exited with code " + exit;
                }
                if (!turnDone && error == null) {
                    error = "provider exited without terminal Turn evidence";
                }
                return completedResult(
                        sessionId, finalText, inputTokens, outputTokens,
                        costUsdMilli, launched.pid(), error);
            }
            finally {
                deleteMcpConfig(mcpConfig);
            }
        }

        private Process startProcess(WriterFence writerFence, Path mcpConfig)
                throws ExecutionPorts.RetryableExecutionException
        {
            ProcessBuilder builder = new ProcessBuilder(
                    buildArgv(request, provider, executable, mcpConfig));
            builder.directory(request.workingDirectory().toFile());
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
                String error)
        {
            return new Result(
                    error == null ? Completion.SUCCEEDED : Completion.FAILED,
                    sessionId,
                    finalText.toString(),
                    inputTokens,
                    outputTokens,
                    costUsdMilli,
                    processPid,
                    error);
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
