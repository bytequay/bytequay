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
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private static final Logger log = LoggerFactory.getLogger(CodexCliDriver.class);
    private static final AgentLaneId LANE_ID = new AgentLaneId("codex");
    private static final int CONTRACT_VERSION = 1;
    /**
     * The oldest Codex CLI whose App Server protocol this lane is verified against.
     *
     * <p>Must equal {@code codexVersion} in the committed reference projection, which is the
     * schema these protocol records were generated from. A newer CLI is left alone: the projection
     * is a floor, not a ceiling.
     */
    static final String MINIMUM_VERSION = "0.151.0";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_QUIESCENCE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration QUIESCENCE_POLL = Duration.ofMillis(100);
    /** A settled answer needs a whole pass that changed nothing, so one clean pass is not enough. */
    private static final int QUIESCENCE_STABLE_PASSES = 2;
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
    private final Path codexHome;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;
    private final Duration quiescenceTimeout;
    private final AtomicReference<String> appServerVersion = new AtomicReference<>();
    private volatile String detectedVersion = "unknown";

    /**
     * @param codexHome ByteQuay's own Codex home; every Codex process this driver starts runs with
     * {@code CODEX_HOME} set to it, so the user's {@code ~/.codex} never reaches a task
     */
    public CodexCliDriver(String executable, ObjectMapper objectMapper, ProcessSupervisor supervisor, Path codexHome)
    {
        this(executable, objectMapper, supervisor, inheritedEnvironment(), codexHome);
    }

    CodexCliDriver(
            String executable,
            ObjectMapper objectMapper,
            ProcessSupervisor supervisor,
            Map<String, String> environment,
            Path codexHome)
    {
        this(executable, objectMapper, supervisor, environment, codexHome, DEFAULT_QUIESCENCE_TIMEOUT);
    }

    CodexCliDriver(
            String executable,
            ObjectMapper objectMapper,
            ProcessSupervisor supervisor,
            Map<String, String> environment,
            Path codexHome,
            Duration quiescenceTimeout)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.codexHome = requireNonNull(codexHome, "codexHome is null").toAbsolutePath();
        // Overrides any inherited value: a shell that exports CODEX_HOME must not redirect ByteQuay's
        // processes into the user's own Codex setup, and the model-facing shell never sees it.
        this.environment = ImmutableMap.<String, String>builder()
                .putAll(Maps.filterKeys(requireNonNull(environment, "environment is null"), name -> !name.equals("CODEX_HOME")))
                .put("CODEX_HOME", this.codexHome.toString())
                .buildOrThrow();
        this.redactor = SecretRedactor.fromEnvironment(this.environment);
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.quiescenceTimeout = requireNonNull(quiescenceTimeout, "quiescenceTimeout is null");
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

    /**
     * Brings the Codex CLI up to the version this lane is verified against, and no further.
     *
     * <p>{@code codex update} always installs the latest release, so running it unconditionally
     * would move a user's CLI on every backend start. It is only worth doing when the installed
     * CLI is older than the protocol this lane was built against; a newer one already satisfies
     * that floor. A version that cannot be read is left alone, because upgrading is the
     * side-effectful choice and nothing here shows it is needed.
     *
     * @return what was done, or why nothing was
     */
    @Override
    public Optional<String> update()
    {
        Optional<String> installed = probe().version();
        if (installed.isEmpty()) {
            return Optional.of("Codex CLI version could not be read; leaving the installation alone");
        }
        if (!isOlderThanMinimum(installed.orElseThrow())) {
            return Optional.of("Codex CLI " + installed.orElseThrow()
                    + " already meets the verified minimum " + MINIMUM_VERSION);
        }
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
                // ByteQuay's home is signed in separately from the user's, so the generic login
                // command would land in the wrong place; readiness carries the exact one.
                return new AgentProviderStatus(
                        LANE_ID,
                        AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                        Optional.of(version),
                        "Codex CLI " + version + " is not signed in for ByteQuay: " + login.output(),
                        capabilities(),
                        Optional.of(signInCommand()));
            }
            // The same check every turn start makes, so a home whose exec-policy rules would be
            // inherited by every task blocks creation here rather than failing at Send.
            try {
                CodexIsolationGuard.rejectRuleFiles(codexHome);
            }
            catch (AgentUnavailableException rules) {
                return status(
                        AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        Optional.of(version),
                        "Codex CLI " + version + " is signed in, but " + rules.getMessage()
                                + ". ByteQuay tasks refuse inherited exec-policy rules; move that rules folder aside, then check again.");
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
                            entry.isDefault(),
                            // The schema's default when the field is absent: text and image.
                            entry.inputModalities().orElse(ImmutableList.of("text", "image"))))
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

    /**
     * Whether an installed version is older than the verified minimum.
     *
     * <p>Compares the numeric release parts, then treats a pre-release as older than the release
     * it precedes, so {@code 0.151.0-alpha.7} counts as older than {@code 0.151.0}. Anything that
     * does not parse is treated as not older, because guessing would upgrade a CLI on no evidence.
     *
     * @param installed the reported version, without the {@code codex-cli } prefix
     * @return true when it is below {@link #MINIMUM_VERSION}
     */
    static boolean isOlderThanMinimum(String installed)
    {
        String[] parts = installed.split("-", 2);
        int[] release;
        try {
            release = Arrays.stream(parts[0].split("\\."))
                    .mapToInt(Integer::parseInt)
                    .toArray();
        }
        catch (NumberFormatException unparseable) {
            return false;
        }
        int[] minimum = Arrays.stream(MINIMUM_VERSION.split("\\."))
                .mapToInt(Integer::parseInt)
                .toArray();
        for (int index = 0; index < Math.max(release.length, minimum.length); index++) {
            int left = index < release.length ? release[index] : 0;
            int right = index < minimum.length ? minimum[index] : 0;
            if (left != right) {
                return left < right;
            }
        }
        // Same release, so only a pre-release of it is older.
        return parts.length > 1;
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

    /** The one command that signs ByteQuay's Codex home in; a plain {@code codex login} would not. */
    private String signInCommand()
    {
        return "CODEX_HOME=\"" + codexHome + "\" " + executable + " login";
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
        /** Every thread this task's process has shown us, root and subagents alike. */
        private final Set<String> observedThreads = ConcurrentHashMap.newKeySet();
        /** Items reported started and not yet reported terminal, by the thread that owns them. */
        private final ConcurrentMap<String, Set<String>> liveItems = new ConcurrentHashMap<>();
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
            ImmutableList.Builder<CodexProtocol.UserInput> input = ImmutableList.builder();
            input.add(new CodexProtocol.TextInput("text", command.message()));
            for (AgentAttachment attachment : command.attachments()) {
                input.add(new CodexProtocol.LocalImageInput("localImage", taskOwnedImage(attachment).toString()));
            }

            // Re-checked before every turn, not once per session: a turn runs under workspace-write
            // and could leave a hook or policy file behind that a later turn would inherit.
            isolation.verify(context.worktree());
            CodexProtocol.TurnStartParams params = new CodexProtocol.TurnStartParams(
                    reference.providerValue(),
                    input.build(),
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
            // An item the previous turn left unresolved belonged to that turn's outcome, which is
            // already decided. Carrying it forward would fail every later turn for the same reason.
            liveItems.clear();
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
            observe(notification);
            try {
                if (rootThread
                        && notification instanceof CodexProtocol.ItemCompletedNotification completed
                        && completed.params().item() instanceof CodexProtocol.AgentMessageItem message) {
                    // Only the root thread speaks for the turn; a subagent's message is activity.
                    running.observeMessage(message.phase(), redactor.redact(message.text()));
                }
                eventMapper.map(notification, rootThread).forEach(events::emit);
                if (rootThread && notification instanceof CodexProtocol.TurnCompletedNotification turnCompleted) {
                    // The root saying it is done is necessary but not sufficient: an item or a
                    // yielded command can still be running. The turn stays live until the process
                    // is proven quiet, and the wait runs elsewhere because the notifications that
                    // would end it are queued behind this handler.
                    CodexRunningTurn completing = running;
                    CodexProtocol.Turn reported = turnCompleted.params().turn();
                    // An interrupt and a natural finish can both report the turn complete. Only the
                    // first starts the wait, or the second would repeat its work and terminate a
                    // background process twice.
                    if (completing.beginSettling()) {
                        Thread.ofVirtual()
                                .name("bytequay-codex-quiescence")
                                .start(() -> settle(completing, reported));
                    }
                }
            }
            catch (RuntimeException failure) {
                CodexRunningTurn failed = running;
                running = null;
                // The process is still running; only a verified stop may release the worktree.
                failed.interrupt(failure, false);
            }
        }

        /**
         * A message, reasoning, or plan item is model output. The App Server leaves one open when
         * a turn is interrupted mid-token, and it cannot have written anything, so waiting for it
         * would fail a cancelled turn over a writer that never existed.
         */
        private static boolean canTouchWorktree(CodexProtocol.ThreadItem item)
        {
            return !(item instanceof CodexProtocol.AgentMessageItem
                    || item instanceof CodexProtocol.ReasoningItem
                    || item instanceof CodexProtocol.PlanTextItem
                    || item instanceof CodexProtocol.ContextCompactionItem
                    || item instanceof CodexProtocol.ModelOutputItem);
        }

        /**
         * The path an image input may name: a verified image inside this task's own runtime
         * directory. Anything else never reaches the App Server, whatever the caller claims.
         */
        private Path taskOwnedImage(AgentAttachment attachment)
        {
            Path path = attachment.path().toAbsolutePath().normalize();
            Path runtime = context.runtimeDirectory().toAbsolutePath().normalize();
            if (!attachment.mediaType().startsWith("image/") || !path.startsWith(runtime)) {
                throw new AgentUnavailableException("Attachment " + attachment.name() + " is not a task-owned image");
            }
            return path;
        }

        /** Records what this process has shown us, so quiescence can be answered from it. */
        private void observe(CodexProtocol.Notification notification)
        {
            observedThreads.add(notification.threadId());
            if (notification instanceof CodexProtocol.ItemStartedNotification started) {
                if (!canTouchWorktree(started.params().item())) {
                    return;
                }
                liveItems
                        .computeIfAbsent(started.threadId(), thread -> ConcurrentHashMap.newKeySet())
                        .add(started.params().item().id());
            }
            else if (notification instanceof CodexProtocol.ItemCompletedNotification completed) {
                Set<String> items = liveItems.get(completed.threadId());
                if (items != null) {
                    items.remove(completed.params().item().id());
                }
            }
        }

        /**
         * Finishes a turn once its process is quiet, or fails it when that cannot be shown.
         *
         * @param turn the turn whose root reported completion
         * @param reported the completion App Server reported for it
         */
        private void settle(CodexRunningTurn turn, CodexProtocol.Turn reported)
        {
            // A cancelled turn is not waiting for its work, it is stopping it, so a yielded
            // command is terminated rather than watched.
            Optional<String> unsettled = awaitQuiescence(turn.cancellationRequested());
            synchronized (this) {
                if (running != turn) {
                    // Cancelled, disconnected, or already finished while we waited.
                    return;
                }
                running = null;
            }
            if (unsettled.isEmpty()) {
                turn.complete(reported);
                return;
            }
            // Never report a clean completion for work that may still be touching the worktree.
            turn.completeUnverifiable(reported, unsettled.orElseThrow());
        }

        /**
         * Waits until nothing this task's process started is still running.
         *
         * <p>App Server v0.151.0 answers {@code thread/read(includeTurns: true)} with
         * {@code list_turns is not supported yet}, so there is no history to close over. What is
         * available is the live item stream, which item notifications are never shed from, plus the
         * background-terminal inventory that reports commands still running after the item that
         * started them went terminal. A pass that changes nothing is not enough on its own, because
         * a subagent can appear between two reads.
         *
         * @return empty when quiet, otherwise what was still outstanding
         */
        private Optional<String> awaitQuiescence(boolean stopBackgroundWork)
        {
            Instant deadline = Instant.now().plus(quiescenceTimeout);
            int stablePasses = 0;
            String lastSeen = "";
            while (stablePasses < QUIESCENCE_STABLE_PASSES) {
                if (closed) {
                    // The session is being released; its process is going with it.
                    return Optional.empty();
                }
                if (Instant.now().isAfter(deadline)) {
                    return Optional.of(lastSeen.isEmpty() ? "the check timed out" : lastSeen);
                }
                Set<String> threads = ImmutableSet.copyOf(observedThreads);
                Optional<String> outstanding = firstOutstanding(threads, stopBackgroundWork);
                if (outstanding.isPresent()) {
                    lastSeen = outstanding.orElseThrow();
                    stablePasses = 0;
                    sleep(QUIESCENCE_POLL);
                    continue;
                }
                // Live and background checks are clear, so confirm against history. This is the
                // only thing that can see an item the notification stream never delivered, and it
                // runs here rather than every pass because paging is not free.
                Optional<String> unfinished = firstUnfinishedInHistory(threads);
                if (unfinished.isPresent()) {
                    lastSeen = unfinished.orElseThrow();
                    stablePasses = 0;
                    sleep(QUIESCENCE_POLL);
                    continue;
                }
                if (!observedThreads.equals(threads)) {
                    // A thread appeared while we were reading, so this pass proved nothing.
                    stablePasses = 0;
                    continue;
                }
                stablePasses++;
                if (stablePasses < QUIESCENCE_STABLE_PASSES) {
                    sleep(QUIESCENCE_POLL);
                }
            }
            return Optional.empty();
        }

        /**
         * Returns the first still-running item or background terminal across the given threads.
         *
         * @param stopBackgroundWork terminate a background terminal instead of waiting for it
         */
        private Optional<String> firstOutstanding(Set<String> threads, boolean stopBackgroundWork)
        {
            for (String thread : threads) {
                Set<String> items = liveItems.get(thread);
                if (items != null && !items.isEmpty()) {
                    return Optional.of("item " + items.iterator().next() + " on thread " + thread);
                }
            }
            for (String thread : threads) {
                Optional<String> terminal = firstBackgroundTerminal(thread, stopBackgroundWork);
                if (terminal.isPresent()) {
                    return terminal;
                }
            }
            return Optional.empty();
        }

        /**
         * Returns the first item any known thread's history still reports unfinished.
         *
         * <p>The live stream can miss an item; history cannot. An inventory that cannot be read is
         * not an empty one.
         *
         * @param threads every thread this process has shown
         * @return what is unfinished, or empty when history agrees nothing is
         */
        private Optional<String> firstUnfinishedInHistory(Set<String> threads)
        {
            for (String thread : threads) {
                Optional<String> cursor = Optional.empty();
                do {
                    CodexProtocol.ThreadItemsListResponse page;
                    try {
                        page = client.request(
                                "thread/items/list",
                                new CodexProtocol.ThreadItemsListParams(thread, cursor),
                                CodexProtocol.ThreadItemsListResponse.class,
                                REQUEST_TIMEOUT);
                    }
                    catch (RuntimeException failure) {
                        return Optional.of("the items of thread " + thread
                                + " could not be listed: " + failure.getMessage());
                    }
                    for (CodexProtocol.ThreadItemEntry entry : page.data()) {
                        // History names the subagents a thread spawned, so a child is found even
                        // when nothing it did ever reached the notification stream. Depth is
                        // bounded to one, so a child has no children of its own to look for.
                        if (entry.item() instanceof CodexProtocol.SubAgentActivityItem subagent) {
                            observedThreads.add(subagent.agentThreadId());
                        }
                        if (!entry.item().terminal()) {
                            return Optional.of("item " + entry.item().id()
                                    + " on thread " + thread + " is still running");
                        }
                    }
                    cursor = page.nextCursor();
                }
                while (cursor.isPresent());
            }
            return Optional.empty();
        }

        private Optional<String> firstBackgroundTerminal(String thread, boolean stopBackgroundWork)
        {
            Optional<String> cursor = Optional.empty();
            do {
                CodexProtocol.ThreadBackgroundTerminalsListResponse page;
                try {
                    page = client.request(
                            "thread/backgroundTerminals/list",
                            new CodexProtocol.ThreadBackgroundTerminalsListParams(thread, cursor),
                            CodexProtocol.ThreadBackgroundTerminalsListResponse.class,
                            REQUEST_TIMEOUT);
                }
                catch (RuntimeException failure) {
                    // An inventory that cannot be read is not an empty one.
                    return Optional.of("the background terminals of thread " + thread
                            + " could not be listed: " + failure.getMessage());
                }
                if (!page.data().isEmpty()) {
                    String processId = page.data().getFirst().processId();
                    if (stopBackgroundWork) {
                        terminate(thread, processId);
                    }
                    // Reported either way, so this pass is never counted as settled. A terminated
                    // process leaves the inventory before the next one reads it.
                    return Optional.of("background terminal " + processId + " on thread " + thread);
                }
                cursor = page.nextCursor();
            }
            while (cursor.isPresent());
            return Optional.empty();
        }

        private void terminate(String thread, String processId)
        {
            try {
                client.request(
                        "thread/backgroundTerminals/terminate",
                        new CodexProtocol.ThreadBackgroundTerminalsTerminateParams(thread, processId),
                        CodexProtocol.EmptyResponse.class,
                        REQUEST_TIMEOUT);
            }
            catch (RuntimeException failure) {
                // Reported as still outstanding either way, so the turn does not settle on a
                // process this failed to stop.
                log.warn("Unable to terminate Codex background terminal {} on thread {}", processId, thread, failure);
            }
        }

        private static void sleep(Duration duration)
        {
            try {
                Thread.sleep(duration);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

        /**
         * The product-permitted decisions for a command approval.
         *
         * <p>Allowing needs the provider to have offered a plain one-time {@code accept}; the
         * amendment and session-scoped variants are never product-permitted. Denying is always
         * available: it can widen nothing, {@code decline} is part of the protocol's decision
         * union, and codex-cli 0.152.1 was measured to honour it while omitting it from
         * {@code availableDecisions} for an escalated command, offering only {@code accept},
         * {@code acceptWithExecpolicyAmendment} and {@code cancel}. Requiring it to be listed
         * would leave such a request with no card at all.
         */
        private static List<ProviderApprovalRequest.Decision> supportedCommandDecisions(
                Optional<List<JsonNode>> offeredDecisions)
        {
            boolean allowOnce = offeredDecisions
                    .map(decisions -> decisions.stream().anyMatch(CodexSession::isPlainAccept))
                    .orElse(true);
            return allowOnce ? ImmutableList.of(ALLOW_ONCE, DENY) : ImmutableList.of(DENY);
        }

        private static boolean isPlainAccept(JsonNode decision)
        {
            return decision.isTextual() && decision.textValue().equals("accept");
        }

        @Override
        public synchronized void onDisconnect(Throwable failure)
        {
            if (running != null) {
                CodexRunningTurn interrupted = running;
                running = null;
                // A disconnect that follows the process exiting knows whether its tree stopped; one
                // that precedes it, such as a protocol failure, cannot claim the worktree is quiet.
                interrupted.interrupt(failure, client.processTreeVerifiedStopped().orElse(false));
            }
        }

        /**
         * Stops the App Server process serving this task.
         *
         * <p>Terminating the process is the stop; asking the turn to interrupt itself first would
         * race the kill and could report the turn quiet before its process was. The close returns
         * only once the process tree is verified stopped, and the running turn is interrupted with
         * that verdict either way, so a turn whose process could not be verified keeps its claim.
         * Repeated closes re-verify rather than returning early, so a refused stop can be retried.
         */
        @Override
        public synchronized void close()
        {
            closed = true;
            CodexRunningTurn interrupted = running;
            running = null;
            try {
                client.close();
            }
            catch (RuntimeException failure) {
                if (interrupted != null) {
                    interrupted.interrupt(failure, false);
                }
                throw failure;
            }
            if (interrupted != null) {
                interrupted.interrupt(new AgentUnavailableException("Codex App Server stopped"), true);
            }
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
                boolean allow = decision.decision() == ProviderApprovalDecision.Decision.ALLOW;
                JsonNode wireDecision = TextNode.valueOf(allow ? "accept" : "decline");
                if (serverRequest instanceof CodexProtocol.CommandApprovalRequest command) {
                    // Only an allow has to be offered; a decline is always legal and widens nothing.
                    if (allow && command.params().availableDecisions().isPresent()
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
        private final AtomicBoolean settling = new AtomicBoolean();
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
                    // The interrupt never reached the process, so nothing says it stopped working.
                    interrupt(failure, false);
                }
            });
        }

        /**
         * Reports a turn whose root finished while its process could still be doing work.
         *
         * <p>Reported FAILED rather than COMPLETED so the task stays open and keeps its worktree:
         * a clean completion here would release the claim over files something may still be
         * writing.
         *
         * @param turn the completion App Server reported
         * @param unsettled what could not be shown stopped
         */
        private void completeUnverifiable(CodexProtocol.Turn turn, String unsettled)
        {
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.FAILED,
                    finalResponse,
                    Optional.of("Codex CLI " + rememberAppServerVersion()
                            + " finished the turn but " + unsettled + " could not be shown stopped"),
                    elapsed(turn),
                    false));
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

        /**
         * Ends the turn without a completion from App Server.
         *
         * @param failure why the turn cannot continue
         * @param providerQuiescent whether the process tree was verified stopped; anything less
         * keeps the task's worktree claim, because the process may still be writing there
         */
        private void interrupt(Throwable failure, boolean providerQuiescent)
        {
            String message = failure.getMessage();
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.INTERRUPTED,
                    finalResponse,
                    Optional.of("Codex CLI " + rememberAppServerVersion() + ": "
                            + (message == null || message.isBlank()
                                    ? "Codex disconnected"
                                    : redactor.redact(message))),
                    Duration.between(startedAt, Instant.now()),
                    providerQuiescent));
        }

        /**
         * Claims the right to wait for this turn to go quiet.
         *
         * @return true for the first caller only
         */
        private boolean beginSettling()
        {
            return settling.compareAndSet(false, true);
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
