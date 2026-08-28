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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.cli.process.Utf8LineBuffer;
import com.bytequay.agent.cli.process.impl.DefaultProcessSupervisor;
import com.bytequay.agent.spi.AgentAttachment;
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
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public final class ClaudeCliDriver
        implements AgentDriver, AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(ClaudeCliDriver.class);
    private static final AgentLaneId LANE_ID = new AgentLaneId("claude");
    private static final int CONTRACT_VERSION = 1;
    private static final Version MINIMUM_VERSION = new Version(2, 1, 200);
    private static final Version SUBAGENT_TEXT_VERSION = new Version(2, 1, 219);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PROCESS_CANCELLATION_GRACE = Duration.ofSeconds(3);
    private static final int MAX_STDOUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 1024 * 1024;
    private static final int MAX_PROTOCOL_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_LINE_BYTES = 16 * 1024;
    private static final int MAX_PROBE_OUTPUT_BYTES = 32 * 1024;
    private static final Set<String> REQUIRED_FLAGS = ImmutableSet.of(
            "--add-dir",
            "--disable-slash-commands",
            "--include-hook-events",
            "--include-partial-messages",
            "--mcp-config",
            "--model",
            "--no-session-persistence",
            "--output-format",
            "--permission-mode",
            "--effort",
            "--settings",
            "--setting-sources",
            "--strict-mcp-config",
            "--tools");
    private static final Set<String> INHERITED_ENVIRONMENT = ImmutableSet.of(
            "ALL_PROXY",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_BASE_URL",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "HOME",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "LANG",
            "LC_ALL",
            "LOGNAME",
            "NODE_EXTRA_CA_CERTS",
            "NO_PROXY",
            "PATH",
            "SHELL",
            "SSL_CERT_DIR",
            "SSL_CERT_FILE",
            "TERM",
            "TMPDIR",
            "USER",
            "XDG_CONFIG_HOME");

    private final String executable;
    private final ProcessSupervisor supervisor;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final ClaudeProtocolCodec codec;
    private final ClaudePermissionBroker permissions;
    private final Set<ClaudeSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean forwardSubagentText = true;
    private volatile boolean closed;

    public ClaudeCliDriver(
            String executable,
            ObjectMapper objectMapper,
            ClaudePermissionBroker permissions)
    {
        this(
                executable,
                objectMapper,
                permissions,
                new DefaultProcessSupervisor(),
                inheritedEnvironment());
    }

    ClaudeCliDriver(
            String executable,
            ObjectMapper objectMapper,
            ClaudePermissionBroker permissions,
            ProcessSupervisor supervisor,
            Map<String, String> environment)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.environment = ImmutableMap.copyOf(requireNonNull(environment, "environment is null"));
        this.redactor = SecretRedactor.fromEnvironment(this.environment);
        this.codec = new ClaudeProtocolCodec(objectMapper);
        this.permissions = requireNonNull(permissions, "permissions is null");
    }

    @Override
    public AgentLaneId id()
    {
        return LANE_ID;
    }

    @Override
    public String label()
    {
        return "Claude Code";
    }

    @Override
    public Optional<String> update()
    {
        try {
            CommandResult result = run(UPDATE_TIMEOUT, executable, "update");
            if (result.exitCode() != 0) {
                throw new AgentUnavailableException("Claude CLI update failed: " + result.output());
            }
            return Optional.of(redactor.redact(result.output()));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Claude CLI update was interrupted", e);
        }
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return new AgentCapabilities(
                true,
                true,
                false,
                true,
                false,
                true,
                forwardSubagentText,
                true,
                true,
                CostProvenance.CLI_ESTIMATE,
                ImmutableSet.copyOf(AgentPermissionPolicy.values()),
                ImmutableSet.of("text/plain", "text/plain; charset=utf-8"));
    }

    @Override
    public AgentProviderStatus probe()
    {
        try {
            CommandResult versionResult = run(executable, "--version");
            if (versionResult.exitCode() != 0) {
                return status(AgentProviderStatus.Health.UNAVAILABLE, Optional.empty(), versionResult.output());
            }
            Optional<Version> parsed = Version.parse(versionResult.output());
            if (parsed.isEmpty()) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.empty(),
                        "Unable to read Claude CLI version");
            }
            Version version = parsed.orElseThrow();
            if (version.compareTo(MINIMUM_VERSION) < 0) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.of(version.toString()),
                        "Unsupported Claude CLI version; expected " + MINIMUM_VERSION + " or newer");
            }

            CommandResult help = run(executable, "--help");
            if (help.exitCode() != 0) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.of(version.toString()),
                        "claude --help failed: " + help.output());
            }
            Optional<String> missing = REQUIRED_FLAGS.stream()
                    .filter(flag -> !help.output().contains(flag))
                    .findFirst();
            if (missing.isPresent()) {
                return status(
                        AgentProviderStatus.Health.UNAVAILABLE,
                        Optional.of(version.toString()),
                        "Claude CLI does not support " + missing.orElseThrow());
            }
            forwardSubagentText = version.compareTo(SUBAGENT_TEXT_VERSION) >= 0
                    && help.output().contains("--forward-subagent-text");
            return status(
                    forwardSubagentText ? AgentProviderStatus.Health.AVAILABLE : AgentProviderStatus.Health.DEGRADED,
                    Optional.of(version.toString()),
                    forwardSubagentText
                            ? "Claude CLI stream-json is available"
                            : "Claude CLI subagent transcript forwarding is unavailable");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status(
                    AgentProviderStatus.Health.UNAVAILABLE,
                    Optional.empty(),
                    "Claude CLI probe was interrupted");
        }
        catch (RuntimeException e) {
            return status(
                    AgentProviderStatus.Health.UNAVAILABLE,
                    Optional.empty(),
                    "Claude CLI is not available: " + e.getMessage());
        }
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        requireNonNull(context, "context is null");
        requireNonNull(events, "events is null");
        if (closed) {
            throw new AgentUnavailableException("Claude driver is closed");
        }
        Optional<AgentSessionRef> existing = context.sessionReference();
        if (existing.isPresent()) {
            AgentSessionRef reference = existing.orElseThrow();
            if (!reference.laneId().equals(LANE_ID) || reference.contractVersion() != CONTRACT_VERSION) {
                throw new AgentUnavailableException("Unsupported Claude session reference");
            }
        }
        String sessionId = existing.map(AgentSessionRef::providerValue)
                .orElseGet(() -> UUID.randomUUID().toString());
        ClaudePermissionBroker.Registration registration = permissions.register(
                context.taskId(),
                context.worktree(),
                context.runtimeDirectory(),
                context.providerDirectory(),
                context.permissionPolicy(),
                context.textOnly(),
                context.approvals(),
                redactor);
        ClaudeSession session = new ClaudeSession(
                new AgentSessionRef(LANE_ID, CONTRACT_VERSION, sessionId),
                context,
                events,
                registration,
                existing.isPresent());
        sessions.add(session);
        return session;
    }

    @Override
    public void close()
    {
        closed = true;
        ImmutableList.copyOf(sessions).forEach(ClaudeSession::close);
    }

    private List<String> argv(
            ClaudePermissionBroker.Registration registration,
            AgentPermissionPolicy permissionPolicy,
            Optional<String> model,
            Optional<String> effort,
            boolean textOnly,
            String sessionId,
            boolean resume,
            List<AgentAttachment> attachments)
    {
        ImmutableList.Builder<String> argv = ImmutableList.builder();
        ImmutableList.Builder<String> allowedTools = ImmutableList.builder();
        argv.add(
                executable,
                "--print",
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--include-hook-events");
        if (forwardSubagentText) {
            argv.add("--forward-subagent-text");
        }
        model.ifPresent(value -> argv.add("--model", value));
        effort.map(ClaudeCliDriver::effortArgument)
                .ifPresent(value -> argv.add("--effort", value));
        argv.add(
                "--strict-mcp-config",
                "--setting-sources=",
                "--settings", registration.settingsFile().toString());
        if (textOnly) {
            argv.add(
                    "--tools=",
                    "--disable-slash-commands",
                    "--no-session-persistence");
        }
        if (permissionPolicy == AgentPermissionPolicy.CI_AUTOFIX) {
            allowedTools.add("mcp__bytequay__read_ci_memory");
        }
        if (!attachments.isEmpty()) {
            argv.add("--add-dir");
            attachments.stream()
                    .map(AgentAttachment::path)
                    .map(Path::getParent)
                    .distinct()
                    .map(Path::toString)
                    .forEach(argv::add);
            allowedTools.addAll(attachments.stream()
                    .map(AgentAttachment::path)
                    .map(ClaudeCliDriver::absoluteReadRule)
                    .iterator());
        }
        List<String> allowed = allowedTools.build();
        if (!allowed.isEmpty()) {
            argv.add("--allowedTools", allowed.stream().collect(joining(",")));
        }
        argv.add(
                "--permission-mode", "auto",
                "--mcp-config", registration.configFile().toString(),
                "--permission-prompt-tool", "mcp__bytequay__permission_prompt",
                resume ? "--resume" : "--session-id", sessionId);
        return argv.build();
    }

    private static String effortArgument(String effort)
    {
        String normalized = effort.toLowerCase(Locale.ROOT);
        return normalized.equals("extra") ? "xhigh" : normalized;
    }

    private AgentProviderStatus status(
            AgentProviderStatus.Health health,
            Optional<String> version,
            String diagnostic)
    {
        String message = diagnostic == null || diagnostic.isBlank() ? health.name().toLowerCase() : diagnostic;
        return new AgentProviderStatus(LANE_ID, health, version, redactor.redact(message), capabilities());
    }

    private CommandResult run(String... command)
            throws InterruptedException
    {
        return run(PROBE_TIMEOUT, command);
    }

    private CommandResult run(Duration maximumRuntime, String... command)
            throws InterruptedException
    {
        StringBuilder output = new StringBuilder();
        ManagedProcess process = supervisor.start(
                processSpec(
                        ImmutableList.copyOf(command),
                        Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                        maximumRuntime,
                        MAX_PROBE_OUTPUT_BYTES,
                        MAX_PROBE_OUTPUT_BYTES),
                (stream, bytes) -> append(output, bytes));
        try {
            ProcessExit result = process.exit().toCompletableFuture()
                    .get(maximumRuntime.toMillis() + 2000, TimeUnit.MILLISECONDS);
            if (result.reason() == ProcessExit.Reason.TIMED_OUT) {
                return new CommandResult(-1, "Claude CLI command timed out");
            }
            return new CommandResult(result.exitCode(), output.toString().trim());
        }
        catch (ExecutionException | TimeoutException e) {
            process.cancel();
            return new CommandResult(-1, "Claude CLI command failed: " + e.getMessage());
        }
    }

    private ProcessSpec processSpec(
            List<String> argv,
            Path workingDirectory,
            Duration maximumRuntime,
            int maximumStdoutBytes,
            int maximumStderrBytes)
    {
        return new ProcessSpec(
                argv,
                workingDirectory,
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

    private record Version(int major, int minor, int patch)
            implements Comparable<Version>
    {
        private static Optional<Version> parse(String text)
        {
            String first = text.strip().split("\\s+", 2)[0];
            String[] parts = first.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Version(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])));
            }
            catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        @Override
        public int compareTo(Version other)
        {
            requireNonNull(other, "other is null");
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
        }

        @Override
        public String toString()
        {
            return major + "." + minor + "." + patch;
        }
    }

    private final class ClaudeSession
            implements AgentSession
    {
        private final AgentSessionRef reference;
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final ClaudePermissionBroker.Registration registration;
        private ClaudeRunningTurn running;
        private boolean resume;
        private boolean sessionClosed;

        private ClaudeSession(
                AgentSessionRef reference,
                AgentSessionContext context,
                AgentEventSink events,
                ClaudePermissionBroker.Registration registration,
                boolean resume)
        {
            this.reference = requireNonNull(reference, "reference is null");
            this.context = requireNonNull(context, "context is null");
            this.events = requireNonNull(events, "events is null");
            this.registration = requireNonNull(registration, "registration is null");
            this.resume = resume;
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
            if (sessionClosed) {
                throw new AgentUnavailableException("Claude session is closed");
            }
            if (running != null && !running.completion().toCompletableFuture().isDone()) {
                throw new AgentUnavailableException("Claude session already has a running turn");
            }
            running = new ClaudeRunningTurn(this, command, resume);
            return running;
        }

        private synchronized void markResumable()
        {
            resume = true;
        }

        private synchronized void finished(ClaudeRunningTurn turn)
        {
            if (running == turn) {
                running = null;
            }
        }

        @Override
        public synchronized void close()
        {
            if (sessionClosed) {
                return;
            }
            sessionClosed = true;
            if (running != null) {
                running.cancel();
            }
            registration.close();
            sessions.remove(this);
        }
    }

    private final class ClaudeRunningTurn
            implements RunningTurn, ProcessOutputSink
    {
        private final ClaudeSession session;
        private final ProviderTurnRef reference;
        private final Instant startedAt = Instant.now();
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        private final ClaudeEventMapper mapper = new ClaudeEventMapper(redactor);
        private final Utf8LineBuffer stdout =
                new Utf8LineBuffer(MAX_PROTOCOL_LINE_BYTES, false, this::receive);
        private final Utf8LineBuffer stderr =
                new Utf8LineBuffer(MAX_DIAGNOSTIC_LINE_BYTES, false, this::diagnostic);
        private final StringBuilder lastDiagnostic = new StringBuilder();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile ManagedProcess process;
        private volatile ClaudeProtocol.ResultMessage result;
        private volatile String interruptionReason;
        private volatile String protocolFailure;
        private volatile boolean stdoutTruncated;

        private ClaudeRunningTurn(ClaudeSession session, AgentTurnCommand command, boolean resume)
        {
            this.session = requireNonNull(session, "session is null");
            this.reference = new ProviderTurnRef(command.turnId());
            List<AgentAttachment> attachments = attachments(session.context, command.attachments());
            ProcessSpec spec = processSpec(
                    argv(
                            session.registration,
                            session.context.permissionPolicy(),
                            session.context.model(),
                            session.context.effort(),
                            session.context.textOnly(),
                            session.reference.providerValue(),
                            resume,
                            attachments),
                    session.context.worktree(),
                    command.maximumRuntime(),
                    MAX_STDOUT_BYTES,
                    MAX_STDERR_BYTES);
            ManagedProcess started = supervisor.start(spec, this);
            this.process = started;
            if (cancelled.get()) {
                started.cancel();
            }
            started.exit().whenComplete(this::processExited);
            byte[] prompt = prompt(command.message(), attachments).getBytes(StandardCharsets.UTF_8);
            started.write(ByteBuffer.wrap(prompt)).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    interruptionReason = "Unable to write Claude prompt: " + failureMessage(failure);
                    started.cancel();
                }
                else {
                    started.closeInput();
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
            if (cancelled.compareAndSet(false, true)) {
                cancelProcess();
            }
        }

        @Override
        public void onOutput(ProcessStream stream, ByteBuffer bytes)
        {
            if (stream == ProcessStream.STDOUT) {
                stdout.accept(bytes);
            }
            else {
                stderr.accept(bytes);
            }
        }

        @Override
        public void onTruncated(ProcessStream stream)
        {
            if (stream == ProcessStream.STDOUT) {
                stdoutTruncated = true;
                cancelProcess();
            }
            else {
                diagnostic("Claude diagnostics were truncated");
            }
        }

        private void receive(String line)
        {
            try {
                ClaudeProtocol.Message message = codec.decode(line);
                if (message.sessionId().isPresent()
                        && !message.sessionId().orElseThrow().equals(session.reference.providerValue())) {
                    protocolFailure = "Claude returned a different session ID";
                    cancelProcess();
                    return;
                }
                message.sessionId().ifPresent(ignored -> session.markResumable());
                if (message instanceof ClaudeProtocol.ResultMessage terminal) {
                    result = terminal;
                }
                mapper.map(message).forEach(session.events::emit);
            }
            catch (RuntimeException failure) {
                session.events.emit(new ProviderAgentEvent.Error(
                        Optional.empty(),
                        Optional.empty(),
                        "claude_stream",
                        redactor.redact(failureMessage(failure)),
                        true));
            }
        }

        private void diagnostic(String line)
        {
            String value = redactor.redact(line);
            log.warn("Claude CLI: {}", value);
            synchronized (lastDiagnostic) {
                lastDiagnostic.setLength(0);
                lastDiagnostic.append(value);
            }
        }

        private void processExited(ProcessExit exit, Throwable failure)
        {
            try {
                stdout.finish();
                stderr.finish();
            }
            catch (RuntimeException outputFailure) {
                failure = outputFailure;
            }
            if (failure != null) {
                interruptionReason = "Claude process failed: " + failureMessage(failure);
            }

            AgentTurnResult turnResult;
            if (interruptionReason != null) {
                turnResult = failedResult(
                        AgentTurnResult.State.INTERRUPTED,
                        redactor.redact(interruptionReason),
                        exit == null ? Duration.between(startedAt, Instant.now()) : exit.duration());
            }
            else if (protocolFailure != null) {
                turnResult = failedResult(
                        AgentTurnResult.State.FAILED,
                        protocolFailure,
                        exit.duration());
            }
            else if (exit.reason() == ProcessExit.Reason.CANCELLED || exit.reason() == ProcessExit.Reason.TIMED_OUT) {
                turnResult = failedResult(
                        AgentTurnResult.State.CANCELLED,
                        "Claude turn was cancelled",
                        exit.duration());
            }
            else if (stdoutTruncated || exit.stdoutTruncated()) {
                turnResult = failedResult(
                        AgentTurnResult.State.FAILED,
                        "Claude protocol output was truncated",
                        exit.duration());
            }
            else if (result == null) {
                turnResult = failedResult(
                        AgentTurnResult.State.FAILED,
                        exitReason(exit),
                        exit.duration());
            }
            else if (!result.successful() || exit.exitCode() != 0) {
                turnResult = failedResult(
                        AgentTurnResult.State.FAILED,
                        redactor.redact(result.errorMessage()),
                        Duration.ofMillis(result.durationMs()));
            }
            else {
                turnResult = new AgentTurnResult(
                        AgentTurnResult.State.COMPLETED,
                        result.result()
                                .filter(value -> !value.isBlank())
                                .map(redactor::redact),
                        Optional.empty(),
                        Duration.ofMillis(result.durationMs()));
            }
            ProviderAgentEvent.LifecyclePhase phase = turnResult.state() == AgentTurnResult.State.CANCELLED
                    || turnResult.state() == AgentTurnResult.State.INTERRUPTED
                    ? ProviderAgentEvent.LifecyclePhase.INTERRUPTED
                    : ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED;
            String message = switch (turnResult.state()) {
                case COMPLETED -> "Claude turn completed";
                case FAILED -> "Claude turn failed";
                case CANCELLED -> "Claude turn cancelled";
                case INTERRUPTED -> "Claude turn interrupted";
            };
            try {
                session.events.emit(new ProviderAgentEvent.Lifecycle(
                        result == null ? Optional.empty() : result.uuid().map(value -> value + ":completed"),
                        Optional.empty(),
                        phase,
                        message));
            }
            catch (RuntimeException eventFailure) {
                turnResult = failedResult(
                        AgentTurnResult.State.INTERRUPTED,
                        "Unable to persist Claude completion: " + failureMessage(eventFailure),
                        exit == null ? Duration.between(startedAt, Instant.now()) : exit.duration());
            }
            completion.complete(turnResult);
            session.finished(this);
        }

        private AgentTurnResult failedResult(
                AgentTurnResult.State state,
                String reason,
                Duration duration)
        {
            return new AgentTurnResult(state, Optional.empty(), Optional.of(reason), duration);
        }

        private String exitReason(ProcessExit exit)
        {
            synchronized (lastDiagnostic) {
                return lastDiagnostic.isEmpty()
                        ? "Claude exited with code " + exit.exitCode() + " without a terminal result"
                        : lastDiagnostic.toString();
            }
        }

        private void cancelProcess()
        {
            ManagedProcess current = process;
            if (current != null) {
                current.cancel();
            }
        }
    }

    private static List<AgentAttachment> attachments(
            AgentSessionContext context,
            List<AgentAttachment> requested)
    {
        if (requested.isEmpty()) {
            return ImmutableList.of();
        }
        try {
            Path taskRoot = context.runtimeDirectory().toRealPath();
            ImmutableList.Builder<AgentAttachment> attachments = ImmutableList.builder();
            for (AgentAttachment attachment : requested) {
                String mediaType = attachment.mediaType().toLowerCase(Locale.ROOT);
                if (!mediaType.equals("text/plain") && !mediaType.startsWith("text/plain;")) {
                    throw new AgentUnavailableException(
                            "Claude does not support attachment type " + attachment.mediaType());
                }
                Path path = attachment.path().toRealPath();
                if (!Files.isRegularFile(path) || !path.startsWith(taskRoot)) {
                    throw new AgentUnavailableException("Claude attachment is outside the task runtime");
                }
                if (attachment.name().contains("\n") || attachment.name().contains("\r")
                        || path.toString().contains("\n") || path.toString().contains("\r")) {
                    throw new AgentUnavailableException("Claude attachment name or path contains a line break");
                }
                attachments.add(new AgentAttachment(attachment.name(), attachment.mediaType(), path));
            }
            return attachments.build();
        }
        catch (java.io.IOException e) {
            throw new AgentUnavailableException("Cannot resolve Claude attachment", e);
        }
    }

    private static String prompt(String message, List<AgentAttachment> attachments)
    {
        StringBuilder prompt = new StringBuilder(message);
        if (!attachments.isEmpty()) {
            prompt.append("\n\nRead-only attachment files:\n");
            attachments.forEach(attachment -> prompt.append("- ")
                    .append(attachment.name())
                    .append(": ")
                    .append(attachment.path())
                    .append('\n'));
        }
        return prompt.append('\n').toString();
    }

    private static String absoluteReadRule(Path path)
    {
        return "Read(/" + path + ")";
    }

    private static String failureMessage(Throwable failure)
    {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
