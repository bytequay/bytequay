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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.cli.process.impl.DefaultProcessSupervisor;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

public final class CodexCliDriver
        implements AgentDriver, AutoCloseable
{
    private static final AgentLaneId LANE_ID = new AgentLaneId("codex");
    private static final int CONTRACT_VERSION = 1;
    private static final String SUPPORTED_VERSION_PREFIX = "0.145.";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration APP_SERVER_RUNTIME = Duration.ofHours(12);
    private static final Duration PROCESS_CANCELLATION_GRACE = Duration.ofSeconds(3);
    private static final int MAX_APP_SERVER_STDOUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_APP_SERVER_STDERR_BYTES = 1024 * 1024;
    private static final int MAX_PROBE_OUTPUT_BYTES = 16 * 1024;
    private static final ImmutableSet<String> INHERITED_ENVIRONMENT = ImmutableSet.of(
            "ALL_PROXY",
            "CODEX_HOME",
            "HOME",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "LANG",
            "LC_ALL",
            "LOGNAME",
            "NODE_EXTRA_CA_CERTS",
            "NO_PROXY",
            "OPENAI_API_KEY",
            "OPENAI_BASE_URL",
            "PATH",
            "SHELL",
            "SSL_CERT_DIR",
            "SSL_CERT_FILE",
            "TERM",
            "TMPDIR",
            "USER",
            "XDG_CONFIG_HOME");

    private static final AgentCapabilities CAPABILITIES = new AgentCapabilities(
            true,
            false,
            true,
            true,
            true,
            false,
            false,
            true,
            true,
            CostProvenance.UNAVAILABLE,
            ImmutableSet.of(AgentPermissionPolicy.MANUAL),
            ImmutableSet.of());

    private final String executable;
    private final ProcessSupervisor supervisor;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final CodexAppServerClient client;

    public CodexCliDriver(String executable, ObjectMapper objectMapper)
    {
        this(
                executable,
                objectMapper,
                new DefaultProcessSupervisor(),
                inheritedEnvironment());
    }

    CodexCliDriver(
            String executable,
            ObjectMapper objectMapper,
            ProcessSupervisor supervisor,
            Map<String, String> environment)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.environment = ImmutableMap.copyOf(requireNonNull(environment, "environment is null"));
        this.redactor = SecretRedactor.fromEnvironment(this.environment);
        this.client = new CodexAppServerClient(
                supervisor,
                processSpec(
                        ImmutableList.of(executable, "app-server", "--listen", "stdio://"),
                        APP_SERVER_RUNTIME,
                        MAX_APP_SERVER_STDOUT_BYTES,
                        MAX_APP_SERVER_STDERR_BYTES),
                objectMapper,
                redactor);
    }

    @Override
    public AgentLaneId id()
    {
        return LANE_ID;
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return CAPABILITIES;
    }

    @Override
    public AgentProviderStatus probe()
    {
        try {
            CommandResult versionResult = run(executable, "--version");
            if (versionResult.exitCode() != 0) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.empty(),
                        "codex --version failed: " + versionResult.output());
            }
            String version = versionResult.output().lines()
                    .filter(line -> line.startsWith("codex-cli "))
                    .map(line -> line.substring("codex-cli ".length()).trim())
                    .findFirst()
                    .orElse("");
            if (version.isBlank()) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.empty(),
                        "Unable to read Codex CLI version");
            }
            if (!version.startsWith(SUPPORTED_VERSION_PREFIX)) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.of(version),
                        "Unsupported Codex CLI version; expected " + SUPPORTED_VERSION_PREFIX + "x");
            }
            CommandResult login = run(executable, "login", "status");
            if (login.exitCode() != 0) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.of(version),
                        login.output());
            }
            return status(AgentProviderStatus.Health.AVAILABLE, Optional.of(version), login.output());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status(
                    AgentProviderStatus.Health.UNAVAILABLE,
                    Optional.empty(),
                    "Codex CLI probe was interrupted");
        }
        catch (RuntimeException e) {
            return status(
                    AgentProviderStatus.Health.UNAVAILABLE,
                    Optional.empty(),
                    "Codex CLI is not available: " + e.getMessage());
        }
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        requireNonNull(context, "context is null");
        requireNonNull(events, "events is null");
        Optional<AgentSessionRef> existing = context.sessionReference();
        String method;
        Object params;
        if (existing.isPresent()) {
            AgentSessionRef reference = existing.orElseThrow();
            if (!reference.laneId().equals(LANE_ID) || reference.contractVersion() != CONTRACT_VERSION) {
                throw new AgentUnavailableException("Unsupported Codex session reference");
            }
            method = "thread/resume";
            params = new CodexProtocol.ThreadResumeParams(
                    reference.providerValue(),
                    context.worktree().toString(),
                    "on-request",
                    "workspace-write");
        }
        else {
            method = "thread/start";
            params = new CodexProtocol.ThreadStartParams(
                    context.worktree().toString(),
                    "on-request",
                    "workspace-write",
                    "ByteQuay");
        }

        CodexProtocol.ThreadResponse response = client.request(
                method,
                params,
                CodexProtocol.ThreadResponse.class,
                REQUEST_TIMEOUT);
        String threadId = response.thread().id();
        CodexSession session = new CodexSession(
                new AgentSessionRef(LANE_ID, CONTRACT_VERSION, threadId),
                context,
                events);
        client.register(threadId, session);
        return session;
    }

    @Override
    public void close()
    {
        client.close();
    }

    private AgentProviderStatus status(
            AgentProviderStatus.Health health,
            Optional<String> version,
            String diagnostic)
    {
        String message = diagnostic == null || diagnostic.isBlank() ? health.name().toLowerCase() : diagnostic;
        return new AgentProviderStatus(LANE_ID, health, version, message, capabilities());
    }

    private CommandResult run(String... command)
            throws InterruptedException
    {
        StringBuilder output = new StringBuilder();
        ManagedProcess process = supervisor.start(
                processSpec(
                        ImmutableList.copyOf(command),
                        Duration.ofSeconds(10),
                        MAX_PROBE_OUTPUT_BYTES,
                        MAX_PROBE_OUTPUT_BYTES),
                (stream, bytes) -> append(output, bytes));
        try {
            ProcessExit result = process.exit().toCompletableFuture().get(12, TimeUnit.SECONDS);
            if (result.reason() == ProcessExit.Reason.TIMED_OUT) {
                return new CommandResult(-1, "Codex CLI probe timed out");
            }
            return new CommandResult(result.exitCode(), output.toString().trim());
        }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            process.cancel();
            return new CommandResult(-1, "Codex CLI probe failed: " + e.getMessage());
        }
    }

    private ProcessSpec processSpec(
            ImmutableList<String> argv,
            Duration maximumRuntime,
            int maximumStdoutBytes,
            int maximumStderrBytes)
    {
        return new ProcessSpec(
                argv,
                Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                environment,
                maximumStdoutBytes,
                maximumStderrBytes,
                maximumRuntime,
                PROCESS_CANCELLATION_GRACE);
    }

    private static void append(StringBuilder output, ByteBuffer bytes)
    {
        synchronized (output) {
            output.append(StandardCharsets.UTF_8.decode(bytes));
        }
    }

    private static Map<String, String> inheritedEnvironment()
    {
        ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
        for (String name : INHERITED_ENVIRONMENT) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        return environment.buildOrThrow();
    }

    private record CommandResult(int exitCode, String output) {}

    private final class CodexSession
            implements AgentSession, CodexAppServerClient.Listener
    {
        private final AgentSessionRef reference;
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final CodexEventMapper eventMapper;
        private CodexRunningTurn running;
        private boolean closed;

        private CodexSession(
                AgentSessionRef reference,
                AgentSessionContext context,
                AgentEventSink events)
        {
            this.reference = requireNonNull(reference, "reference is null");
            this.context = requireNonNull(context, "context is null");
            this.events = requireNonNull(events, "events is null");
            this.eventMapper = new CodexEventMapper(context.worktree(), redactor);
        }

        @Override
        public AgentSessionRef reference()
        {
            return reference;
        }

        @Override
        public synchronized RunningTurn start(AgentTurnCommand command)
        {
            requireNonNull(command, "command is null");
            if (closed) {
                throw new AgentUnavailableException("Codex session is closed");
            }
            if (running != null && !running.completion().toCompletableFuture().isDone()) {
                throw new AgentUnavailableException("Codex session already has a running turn");
            }
            if (!command.attachments().isEmpty()) {
                throw new AgentUnavailableException("Codex attachments are not enabled");
            }

            CodexProtocol.TurnStartParams params = new CodexProtocol.TurnStartParams(
                    reference.providerValue(),
                    ImmutableList.of(new CodexProtocol.TextInput("text", command.message())));
            CodexProtocol.TurnStartResponse response = client.request(
                    "turn/start",
                    params,
                    CodexProtocol.TurnStartResponse.class,
                    REQUEST_TIMEOUT);
            String turnId = response.turn().id();
            running = new CodexRunningTurn(reference.providerValue(), turnId, command.maximumRuntime());
            return running;
        }

        @Override
        public synchronized void onNotification(CodexProtocol.Notification notification)
        {
            if (running == null) {
                return;
            }
            if (notification.turnId().isPresent()
                    && !notification.turnId().orElseThrow().equals(running.reference().value())) {
                return;
            }
            try {
                if (notification instanceof CodexProtocol.ItemCompletedNotification completed
                        && completed.params().item() instanceof CodexProtocol.AgentMessageItem message) {
                    running.observeMessage(message.phase(), redactor.redact(message.text()));
                }
                eventMapper.map(notification).forEach(events::emit);
                if (notification instanceof CodexProtocol.TurnCompletedNotification turnCompleted) {
                    CodexRunningTurn completed = running;
                    running = null;
                    completed.complete(turnCompleted.params().turn());
                }
            }
            catch (RuntimeException failure) {
                CodexRunningTurn failed = running;
                running = null;
                failed.interrupt(failure);
            }
        }

        @Override
        public CompletionStage<?> onRequest(CodexProtocol.ServerRequest serverRequest)
        {
            ProviderApprovalRequest.Kind kind;
            String title;
            String detail;
            long startedAtMs;
            if (serverRequest instanceof CodexProtocol.CommandApprovalRequest approval) {
                kind = ProviderApprovalRequest.Kind.COMMAND;
                title = "Allow Codex command";
                detail = approval.params().command()
                        .filter(value -> !value.isBlank())
                        .or(() -> approval.params().reason().filter(value -> !value.isBlank()))
                        .orElse("Command approval");
                detail = redactor.redact(detail);
                startedAtMs = approval.params().startedAtMs();
            }
            else if (serverRequest instanceof CodexProtocol.FileChangeApprovalRequest approval) {
                kind = ProviderApprovalRequest.Kind.FILE_CHANGE;
                title = "Allow Codex file change";
                detail = approval.params().reason()
                        .filter(value -> !value.isBlank())
                        .orElse("File change approval");
                detail = redactor.redact(detail);
                startedAtMs = approval.params().startedAtMs();
            }
            else {
                return CompletableFuture.failedFuture(
                        new AgentUnavailableException("Unsupported Codex request"));
            }

            ProviderApprovalRequest approvalRequest = new ProviderApprovalRequest(
                    String.valueOf(serverRequest.id()),
                    kind,
                    title,
                    detail,
                    ImmutableList.of(detail),
                    Instant.ofEpochMilli(startedAtMs));
            return context.approvals().request(approvalRequest, APPROVAL_TIMEOUT)
                    .thenApply(decision -> approvalResponse(decision));
        }

        @Override
        public synchronized void onDisconnect(Throwable failure)
        {
            if (running != null) {
                CodexRunningTurn interrupted = running;
                running = null;
                interrupted.interrupt(failure);
            }
        }

        @Override
        public synchronized void close()
        {
            if (closed) {
                return;
            }
            closed = true;
            if (running != null) {
                running.cancel();
            }
            try {
                client.request(
                        "thread/unsubscribe",
                        new CodexProtocol.ThreadParams(reference.providerValue()),
                        CodexProtocol.EmptyResponse.class,
                        Duration.ofSeconds(5));
            }
            catch (RuntimeException ignored) {
                // The backend is already releasing this local session.
            }
            client.unregister(reference.providerValue(), this);
        }

        private CodexProtocol.ApprovalResponse approvalResponse(ProviderApprovalDecision decision)
        {
            return new CodexProtocol.ApprovalResponse(
                    decision.decision() == ProviderApprovalDecision.Decision.ALLOW ? "accept" : "decline");
        }
    }

    private final class CodexRunningTurn
            implements RunningTurn
    {
        private final ProviderTurnRef reference;
        private final String threadId;
        private final Instant startedAt = Instant.now();
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile Optional<String> finalResponse = Optional.empty();

        private CodexRunningTurn(String threadId, String turnId, Duration maximumRuntime)
        {
            this.threadId = requireNonNull(threadId, "threadId is null");
            this.reference = new ProviderTurnRef(turnId);
            CompletableFuture.delayedExecutor(maximumRuntime.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!completion.isDone()) {
                            cancel();
                        }
                    });
        }

        @Override
        public ProviderTurnRef reference()
        {
            return reference;
        }

        @Override
        public CompletionStage<AgentTurnResult> completion()
        {
            return completion;
        }

        @Override
        public void cancel()
        {
            if (!cancellationRequested.compareAndSet(false, true)) {
                return;
            }
            Thread.ofVirtual().name("bytequay-codex-cancel").start(() -> {
                try {
                    client.request(
                            "turn/interrupt",
                            new CodexProtocol.TurnParams(threadId, reference.value()),
                            CodexProtocol.EmptyResponse.class,
                            Duration.ofSeconds(10));
                }
                catch (RuntimeException failure) {
                    interrupt(failure);
                }
            });
        }

        private void observeMessage(Optional<CodexProtocol.MessagePhase> phase, String text)
        {
            if (phase.orElse(CodexProtocol.MessagePhase.UNKNOWN) == CodexProtocol.MessagePhase.FINAL_ANSWER
                    || finalResponse.isEmpty()) {
                finalResponse = text.isBlank() ? Optional.empty() : Optional.of(text);
            }
        }

        private void complete(CodexProtocol.Turn turn)
        {
            AgentTurnResult.State state = switch (turn.status()) {
                case COMPLETED -> AgentTurnResult.State.COMPLETED;
                case INTERRUPTED -> cancellationRequested.get()
                        ? AgentTurnResult.State.CANCELLED
                        : AgentTurnResult.State.INTERRUPTED;
                case FAILED, IN_PROGRESS, UNKNOWN -> AgentTurnResult.State.FAILED;
            };
            Optional<String> reason = state == AgentTurnResult.State.COMPLETED
                    ? Optional.empty()
                    : Optional.of(turn.error()
                            .map(CodexProtocol.TurnError::message)
                            .filter(value -> !value.isBlank())
                            .map(redactor::redact)
                            .orElse("Codex turn " + turn.status().name().toLowerCase()));
            completion.complete(new AgentTurnResult(state, finalResponse, reason, elapsed(turn)));
        }

        private void interrupt(Throwable failure)
        {
            String message = failure.getMessage();
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.INTERRUPTED,
                    finalResponse,
                    Optional.of(message == null || message.isBlank()
                            ? "Codex disconnected"
                            : redactor.redact(message)),
                    Duration.between(startedAt, Instant.now())));
        }

        private Duration elapsed(CodexProtocol.Turn turn)
        {
            return turn.durationMs()
                    .map(Duration::ofMillis)
                    .orElseGet(() -> Duration.between(startedAt, Instant.now()));
        }
    }
}
