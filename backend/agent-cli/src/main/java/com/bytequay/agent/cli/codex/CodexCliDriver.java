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
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
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
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Runs Codex tasks, one App Server process per task.
 *
 * <p>The process working directory is the task worktree, and that is the whole execution boundary:
 * under {@code workspace-write} App Server's default environment confines writes to its own
 * working directory, so nothing registers an execution environment and nothing declares the
 * experimental API. The launch owner is the task id, so a process recovered after a crash is
 * attributable to the task that leaked it.
 *
 * <p>Opening a session is what starts a process. The driver holds none between calls; the session
 * owns its process and closes it.
 *
 * <pre>
 *   open(context, events)
 *     |- appServerClient(taskId, worktree)     ProcessOwner(APP_SERVER, taskId)
 *     '- new CodexSession(client, ...)
 *          1. client.listen(this)              before any thread exists, so no message
 *                                              can arrive with nowhere to go
 *          2. client.start()                   gated launch, then initialize/initialized
 *          3. thread/start | thread/resume     in the worktree, no environment selected
 *
 *   session.start(command)
 *     |- CodexIsolationGuard.verify(worktree)  re-run every turn: a turn runs under
 *     |                                        workspace-write and can leave a hook behind
 *     '- turn/start                            carries the turn's own model/effort snapshot
 * </pre>
 *
 * <p>Inbound messages arrive on the dispatcher's consumer thread, never the reader. Every thread
 * in this process belongs to this task, so a non-root thread is one of its subagents: its activity
 * is reported, while turn completion and the final answer stay gated on the root thread, because a
 * subagent's {@code turn/completed} arrives before the root's.
 *
 * <pre>
 *   onNotification --&gt; eventMapper --&gt; AgentEventSink --&gt; agent-core (sequenced, then SSE)
 *   onRequest      --&gt; approval request --&gt; agent-core --&gt; user --&gt; JSON-RPC response
 * </pre>
 */
public final class CodexCliDriver
        implements AgentDriver, AutoCloseable
{
    private static final AgentLaneId LANE_ID = new AgentLaneId("codex");
    private static final int CONTRACT_VERSION = 1;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration APP_SERVER_RUNTIME = Duration.ofHours(12);
    private static final Duration PROCESS_CANCELLATION_GRACE = Duration.ofSeconds(3);
    private static final int MAX_APP_SERVER_STDOUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_APP_SERVER_STDERR_BYTES = 1024 * 1024;
    private static final int MAX_PROBE_OUTPUT_BYTES = 16 * 1024;
    private static final Set<String> INHERITED_ENVIRONMENT = ImmutableSet.of(
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
            ImmutableSet.of(AgentPermissionPolicy.AUTO),
            ImmutableSet.of());

    private final String executable;
    private final ProcessSupervisor supervisor;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> appServerVersion = new AtomicReference<>();
    private volatile String detectedVersion = "unknown";

    public CodexCliDriver(String executable, ObjectMapper objectMapper, ProcessSupervisor supervisor)
    {
        this(executable, objectMapper, supervisor, inheritedEnvironment());
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
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * Builds an unstarted App Server connection owned by one caller.
     *
     * @param ownerId opaque owner recorded against the launch
     * @param workingDirectory directory the process runs in, which is also its sandbox root
     * @return the connection, which the caller must start and close
     */
    private CodexAppServerClient appServerClient(String ownerId, Path workingDirectory)
    {
        return new CodexAppServerClient(
                supervisor,
                processSpec(
                        new ProcessOwner(ProcessOwner.Kind.APP_SERVER, ownerId),
                        CodexLaunchProfile.arguments(executable),
                        workingDirectory,
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
    public String label()
    {
        return "Codex";
    }

    @Override
    public Optional<String> update()
    {
        try {
            CommandResult result = run(UPDATE_TIMEOUT, executable, "update");
            if (result.exitCode() != 0) {
                throw new AgentUnavailableException("Codex CLI update failed: " + result.output());
            }
            return Optional.of(redactor.redact(result.output()));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Codex CLI update was interrupted", e);
        }
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
                        AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        Optional.empty(),
                        "codex --version failed: " + versionResult.output());
            }
            String version = versionResult.output().lines()
                    .filter(line -> line.startsWith("codex-cli "))
                    .map(line -> line.substring("codex-cli ".length()).trim())
                    .findFirst()
                    .orElseGet(() -> versionResult.output().lines()
                            .map(String::trim)
                            .filter(line -> !line.isBlank())
                            .findFirst()
                            .orElse(""));
            if (version.isBlank()) {
                return status(
                        AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        Optional.empty(),
                        "Unable to read Codex CLI version");
            }
            detectedVersion = version;
            CommandResult login = run(executable, "login", "status");
            if (login.exitCode() != 0) {
                return status(
                        AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                        Optional.of(version),
                        "Codex CLI " + version + " is not ready: " + login.output());
            }
            return status(AgentProviderStatus.Readiness.READY, Optional.of(version), login.output());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return statusWithDetectedVersion(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, "probe was interrupted");
        }
        catch (RuntimeException e) {
            AgentProviderStatus.Readiness readiness = e.getCause() instanceof IOException
                    ? AgentProviderStatus.Readiness.CLI_MISSING
                    : AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
            return statusWithDetectedVersion(readiness, "is not available: " + e.getMessage());
        }
    }

    @Override
    public List<ProviderModel> models()
    {
        rememberAppServerVersion();
        // The catalog belongs to no task, so it gets its own short-lived connection rather than a
        // process kept alive all day to answer a question asked once at startup.
        try (CodexAppServerClient catalog = appServerClient(
                "codex-model-catalog",
                Path.of(System.getProperty("user.dir")).toAbsolutePath())) {
            catalog.start();
            CodexProtocol.ModelListResponse response = catalog.request(
                    "model/list",
                    new CodexProtocol.ModelListParams(),
                    CodexProtocol.ModelListResponse.class,
                    REQUEST_TIMEOUT);
            return response.data().stream()
                    .filter(entry -> !entry.hidden())
                    .map(entry -> new ProviderModel(
                            entry.id(),
                            entry.displayName(),
                            entry.description(),
                            entry.supportedReasoningEfforts().stream()
                                    .map(CodexProtocol.ReasoningEffortOption::reasoningEffort)
                                    .collect(toImmutableList()),
                            Optional.of(entry.defaultReasoningEffort()),
                            entry.isDefault()))
                    .collect(toImmutableList());
        }
        catch (RuntimeException failure) {
            throw appServerFailure("model catalog", failure);
        }
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        requireNonNull(context, "context is null");
        requireNonNull(events, "events is null");
        rememberAppServerVersion();
        // The process runs in the task worktree because that directory is also its sandbox root:
        // App Server's default environment confines a turn's writes to its own working directory,
        // which is why a task needs no separately registered execution environment.
        CodexAppServerClient client = appServerClient(context.taskId(), context.worktree());
        try {
            return new CodexSession(client, context, events);
        }
        catch (RuntimeException failure) {
            // The process is running by the time anything below it can fail, and nothing else
            // holds a reference that would ever close it.
            client.close();
            throw failure;
        }
    }

    @Override
    public void close()
    {
        // Each session owns the App Server process serving its task and closes it. The driver
        // itself holds no process between calls.
    }

    private AgentProviderStatus status(
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic)
    {
        String message = diagnostic == null || diagnostic.isBlank() ? readiness.name().toLowerCase() : diagnostic;
        return new AgentProviderStatus(LANE_ID, readiness, version, message, capabilities());
    }

    private AgentProviderStatus statusWithDetectedVersion(AgentProviderStatus.Readiness readiness, String diagnostic)
    {
        Optional<String> version = detectedVersion.equals("unknown") ? Optional.empty() : Optional.of(detectedVersion);
        String label = version.map(value -> "Codex CLI " + value).orElse("Codex CLI");
        return status(readiness, version, label + " " + diagnostic);
    }

    private AgentUnavailableException appServerFailure(String operation, RuntimeException failure)
    {
        String detail = failure.getMessage();
        return new AgentUnavailableException(
                "Codex CLI " + rememberAppServerVersion() + " failed during " + operation + ": "
                        + redactor.redact(detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail),
                failure);
    }

    private String rememberAppServerVersion()
    {
        String version = appServerVersion.get();
        if (version != null) {
            return version;
        }
        version = detectedVersion;
        if (version.equals("unknown")) {
            version = probe().version().orElse("unknown");
        }
        if (version.equals("unknown")) {
            return version;
        }
        appServerVersion.compareAndSet(null, version);
        return appServerVersion.get();
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
        ManagedProcess process = supervisor.prepare(
                processSpec(
                        new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "codex-cli"),
                        ImmutableList.copyOf(command),
                        Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                        maximumRuntime,
                        MAX_PROBE_OUTPUT_BYTES,
                        MAX_PROBE_OUTPUT_BYTES))
                .start((stream, bytes) -> append(output, bytes));
        try {
            ProcessExit result = process.exit().toCompletableFuture()
                    .get(maximumRuntime.toMillis() + 2000, TimeUnit.MILLISECONDS);
            supervisor.acknowledge(process.launchId());
            if (result.reason() == ProcessExit.Reason.TIMED_OUT) {
                return new CommandResult(-1, "Codex CLI command timed out");
            }
            return new CommandResult(result.exitCode(), output.toString().trim());
        }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            process.cancel();
            return new CommandResult(-1, "Codex CLI command failed: " + e.getMessage());
        }
    }

    private ProcessSpec processSpec(
            ProcessOwner owner,
            List<String> argv,
            Path workingDirectory,
            Duration maximumRuntime,
            int maximumStdoutBytes,
            int maximumStderrBytes)
    {
        return new ProcessSpec(
                owner,
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

    private final class CodexSession
            implements AgentSession, CodexMessageDispatcher.Listener
    {
        private final CodexAppServerClient client;
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final CodexEventMapper eventMapper;
        private final CodexIsolationGuard isolation;
        private final AgentSessionRef reference;
        private CodexRunningTurn running;
        private boolean closed;

        private CodexSession(
                CodexAppServerClient client,
                AgentSessionContext context,
                AgentEventSink events)
        {
            this.client = requireNonNull(client, "client is null");
            this.context = requireNonNull(context, "context is null");
            this.events = requireNonNull(events, "events is null");
            this.eventMapper = new CodexEventMapper(context.worktree(), redactor);
            this.isolation = new CodexIsolationGuard(client);
            // Attached before the process is started, so nothing App Server emits can arrive
            // before there is a session to receive it.
            client.listen(this);
            client.start();
            this.reference = new AgentSessionRef(LANE_ID, CONTRACT_VERSION, openThread(context));
        }

        /**
         * Starts or resumes this task's Codex thread.
         *
         * @param context the task
         * @return the provider thread identifier
         */
        private String openThread(AgentSessionContext context)
        {
            Optional<AgentSessionRef> existing = context.sessionReference();
            String method;
            Object params;
            if (existing.isPresent()) {
                AgentSessionRef previous = existing.orElseThrow();
                if (!previous.laneId().equals(LANE_ID) || previous.contractVersion() != CONTRACT_VERSION) {
                    throw new AgentUnavailableException("Unsupported Codex session reference");
                }
                method = "thread/resume";
                params = new CodexProtocol.ThreadResumeParams(
                        previous.providerValue(),
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
            try {
                return client.request(method, params, CodexProtocol.ThreadResponse.class, REQUEST_TIMEOUT)
                        .thread()
                        .id();
            }
            catch (RuntimeException failure) {
                throw appServerFailure(method, failure);
            }
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

            // Re-checked before every turn, not once per session: a turn runs under workspace-write
            // and could leave a hook or policy file behind that a later turn would inherit.
            isolation.verify(context.worktree());
            CodexProtocol.TurnStartParams params = new CodexProtocol.TurnStartParams(
                    reference.providerValue(),
                    ImmutableList.of(new CodexProtocol.TextInput("text", command.message())),
                    // The turn carries the settings that were snapshotted when it was accepted, so
                    // a default changed after acceptance cannot rewrite the turn that is starting.
                    command.model().or(context::model),
                    command.effort().or(context::effort));
            rememberAppServerVersion();
            CodexProtocol.TurnStartResponse response;
            try {
                response = client.request(
                        "turn/start",
                        params,
                        CodexProtocol.TurnStartResponse.class,
                        REQUEST_TIMEOUT);
            }
            catch (RuntimeException failure) {
                throw appServerFailure("turn/start", failure);
            }
            String turnId = response.turn().id();
            running = new CodexRunningTurn(client, reference.providerValue(), turnId, command.maximumRuntime());
            return running;
        }

        @Override
        public synchronized void onNotification(CodexProtocol.Notification notification)
        {
            if (running == null) {
                return;
            }
            // Every thread in this process belongs to this task, so a thread that is not the root
            // is one of its subagents.
            boolean rootThread = notification.threadId().equals(reference.providerValue());
            if (rootThread
                    && notification.turnId().isPresent()
                    && !notification.turnId().orElseThrow().equals(running.reference().value())) {
                return;
            }
            if (!rootThread && !subagentActivity(notification)) {
                return;
            }
            try {
                if (rootThread
                        && notification instanceof CodexProtocol.ItemCompletedNotification completed
                        && completed.params().item() instanceof CodexProtocol.AgentMessageItem message) {
                    // Only the root thread speaks for the turn; a subagent's message is activity.
                    running.observeMessage(message.phase(), redactor.redact(message.text()));
                }
                eventMapper.map(notification, rootThread).forEach(events::emit);
                if (rootThread && notification instanceof CodexProtocol.TurnCompletedNotification turnCompleted) {
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

        /**
         * Whether a subagent's notification may be reported as this task's activity.
         *
         * <p>An allowlist rather than an exclusion list, because anything that speaks for the turn
         * rather than describing work is wrong when it comes from a subagent. A subagent keeps its
         * own cumulative token counter and its own plan, and feeding either into the root turn
         * overwrites the task's real numbers with a second, unrelated running total.
         *
         * @param notification an inbound notification from a non-root thread
         * @return true when it describes work the task should show
         */
        private boolean subagentActivity(CodexProtocol.Notification notification)
        {
            return notification instanceof CodexProtocol.ItemStartedNotification
                    || notification instanceof CodexProtocol.ItemCompletedNotification
                    || notification instanceof CodexProtocol.AgentMessageDeltaNotification
                    || notification instanceof CodexProtocol.CommandOutputNotification
                    || notification instanceof CodexProtocol.ErrorNotification;
        }

        @Override
        public CompletionStage<?> onRequest(CodexProtocol.ServerRequest serverRequest)
        {
            CodexRunningTurn expectedTurn;
            synchronized (this) {
                if (closed || running == null) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex request does not belong to the active turn"));
                }
                if (serverRequest.threadId().isBlank()) {
                    // A legacy request names a conversation rather than a thread, so it cannot be
                    // attributed to this task's turn at all.
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex request has no active thread"));
                }
                // A subagent asks on its own thread and turn. It runs inside this task's process,
                // so its approval belongs to the turn that spawned it.
                if (serverRequest.threadId().equals(reference.providerValue())
                        && (serverRequest.turnId().isEmpty()
                                || !serverRequest.turnId().orElseThrow().equals(running.reference().value()))) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex request does not belong to the active turn"));
                }
                expectedTurn = running;
            }
            if (expectedTurn.cancellationRequested()
                    || expectedTurn.completion().toCompletableFuture().isDone()) {
                return CompletableFuture.failedFuture(
                        new AgentUnavailableException("Codex request does not belong to the active turn"));
            }
            // Approval registration crosses into agent-core and may take the task lock.
            // Never hold provider session/turn monitors across that callback.
            return registerApproval(serverRequest, expectedTurn);
        }

        private CompletionStage<?> registerApproval(
                CodexProtocol.ServerRequest serverRequest,
                CodexRunningTurn expectedTurn)
        {
            ProviderApprovalRequest.Kind kind;
            String title;
            String detail;
            String itemId;
            List<ProviderApprovalRequest.Decision> availableDecisions;
            long startedAtMs;
            if (serverRequest instanceof CodexProtocol.CommandApprovalRequest approval) {
                if (approval.params().additionalPermissions().isPresent()
                        || approval.params().networkApprovalContext().isPresent()) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex command approval requests permissions ByteQuay cannot represent"));
                }
                if (approval.params().kind().filter(commandKind -> !commandKind.equals("command")).isPresent()) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Unsupported Codex command approval kind"));
                }
                Optional<String> command = approval.params().command().filter(value -> !value.isBlank());
                if (command.isEmpty()) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex command approval has no displayable command"));
                }
                kind = ProviderApprovalRequest.Kind.COMMAND;
                title = "Allow Codex command";
                detail = redactor.redact(command.orElseThrow());
                itemId = approval.params().itemId();
                availableDecisions = supportedCommandDecisions(approval.params().availableDecisions());
                if (availableDecisions.isEmpty()) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex command approval offers no ByteQuay-supported decision"));
                }
                startedAtMs = approval.params().startedAtMs();
            }
            else if (serverRequest instanceof CodexProtocol.FileChangeApprovalRequest approval) {
                if (approval.params().grantRoot().isPresent()) {
                    return CompletableFuture.failedFuture(
                            new AgentUnavailableException("Codex file approval requests a scoped grant ByteQuay cannot represent"));
                }
                kind = ProviderApprovalRequest.Kind.FILE_CHANGE;
                title = "Allow Codex file change";
                detail = approval.params().reason()
                        .filter(value -> !value.isBlank())
                        .orElse("File change approval");
                detail = redactor.redact(detail);
                itemId = approval.params().itemId();
                availableDecisions = ImmutableList.of(
                        ALLOW_ONCE,
                        DENY);
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
                    availableDecisions,
                    new ProviderAgentEventSource(
                            Optional.of(serverRequest.threadId()),
                            Optional.empty(),
                            Optional.empty(),
                            serverRequest.turnId(),
                            Optional.of(itemId)),
                    Instant.ofEpochMilli(startedAtMs));
            return context.approvals().request(approvalRequest, APPROVAL_TIMEOUT)
                    .thenApply(decision -> approvalResponse(serverRequest, decision, expectedTurn));
        }

        private static List<ProviderApprovalRequest.Decision> supportedCommandDecisions(
                Optional<List<JsonNode>> offeredDecisions)
        {
            if (offeredDecisions.isEmpty()) {
                return ImmutableList.of(
                        ALLOW_ONCE,
                        DENY);
            }
            boolean allowOnce = offeredDecisions.orElseThrow().stream()
                    .anyMatch(decision -> decision.isTextual() && decision.textValue().equals("accept"));
            boolean deny = offeredDecisions.orElseThrow().stream()
                    .anyMatch(decision -> decision.isTextual() && decision.textValue().equals("decline"));
            if (!deny) {
                return ImmutableList.of();
            }
            ImmutableList.Builder<ProviderApprovalRequest.Decision> supported = ImmutableList.builder();
            if (allowOnce) {
                supported.add(ALLOW_ONCE);
            }
            supported.add(DENY);
            return supported.build();
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
            // Closing the connection terminates the App Server process serving this task, which
            // releases every thread it held. There is nothing left to unsubscribe from.
            client.close();
        }

        private synchronized Object approvalResponse(
                CodexProtocol.ServerRequest serverRequest,
                ProviderApprovalDecision decision,
                CodexRunningTurn expectedTurn)
        {
            synchronized (expectedTurn) {
                if (closed
                        || running != expectedTurn
                        || expectedTurn.cancellationRequested()
                        || expectedTurn.completion().toCompletableFuture().isDone()) {
                    throw new AgentUnavailableException("Codex approval no longer belongs to the active turn");
                }
                if (decision.scopedPermission().isPresent()) {
                    throw new AgentUnavailableException("Codex approval does not support the requested scoped permission");
                }
                JsonNode wireDecision = TextNode.valueOf(
                        decision.decision() == ProviderApprovalDecision.Decision.ALLOW ? "accept" : "decline");
                if (serverRequest instanceof CodexProtocol.CommandApprovalRequest command) {
                    if (command.params().availableDecisions().isPresent()
                            && !command.params().availableDecisions().orElseThrow().contains(wireDecision)) {
                        throw new AgentUnavailableException(
                                "Codex command approval did not offer decision " + wireDecision.textValue());
                    }
                    return new CodexProtocol.CommandApprovalResponse(wireDecision);
                }
                if (serverRequest instanceof CodexProtocol.FileChangeApprovalRequest) {
                    return new CodexProtocol.FileChangeApprovalResponse(wireDecision);
                }
                throw new AgentUnavailableException("Unsupported Codex approval response");
            }
        }
    }

    private final class CodexRunningTurn
            implements RunningTurn
    {
        private final CodexAppServerClient client;
        private final ProviderTurnRef reference;
        private final String threadId;
        private final Instant startedAt = Instant.now();
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile Optional<String> finalResponse = Optional.empty();

        private CodexRunningTurn(
                CodexAppServerClient client,
                String threadId,
                String turnId,
                Duration maximumRuntime)
        {
            this.client = requireNonNull(client, "client is null");
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
        public synchronized void cancel()
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
            String detail = turn.error()
                    .map(CodexProtocol.TurnError::message)
                    .filter(value -> !value.isBlank())
                    .map(redactor::redact)
                    .orElse("Codex turn " + turn.status().name().toLowerCase());
            Optional<String> reason = switch (state) {
                case COMPLETED -> Optional.empty();
                case CANCELLED -> Optional.of(detail);
                case FAILED, INTERRUPTED -> Optional.of("Codex CLI " + rememberAppServerVersion() + ": " + detail);
            };
            completion.complete(new AgentTurnResult(state, finalResponse, reason, elapsed(turn)));
        }

        private void interrupt(Throwable failure)
        {
            String message = failure.getMessage();
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.INTERRUPTED,
                    finalResponse,
                    Optional.of("Codex CLI " + rememberAppServerVersion() + ": "
                            + (message == null || message.isBlank()
                                    ? "Codex disconnected"
                                    : redactor.redact(message))),
                    Duration.between(startedAt, Instant.now())));
        }

        private synchronized boolean cancellationRequested()
        {
            return cancellationRequested.get();
        }

        private Duration elapsed(CodexProtocol.Turn turn)
        {
            return turn.durationMs()
                    .map(Duration::ofMillis)
                    .orElseGet(() -> Duration.between(startedAt, Instant.now()));
        }
    }
}
