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

import com.bytequay.agent.cli.ProviderInstructions;
import com.bytequay.agent.cli.mcp.McpRegistration;
import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.cli.process.GitCliEnvironment;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.ProviderCli;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RelatedWorkspaceBridge;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.core.process.Utf8LineBuffer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.bytequay.agent.cli.process.ProviderCli.failureMessage;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public final class ClaudeCliDriver
        implements AgentDriver, AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(ClaudeCliDriver.class);
    private static final int CONTRACT_VERSION = 1;
    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Version MINIMUM_VERSION = Version.parse("2.1.200");
    private static final Version SUBAGENT_TEXT_VERSION = Version.parse("2.1.219");
    // The release whose PreToolUse hook input was verified to mark a child's tool call with agent_id, which the review child gate reads.
    private static final Version REVIEW_CHILD_VERSION = Version.parse("2.1.278");
    // From this release a headless resume carries total_cost_usd on from the total the session's transcript saved. Earlier ones began every process at zero.
    private static final Version SESSION_COST_VERSION = Version.parse("2.1.277");
    // Verified command_lifecycle receipts bind streaming input and completion to each submitted UUID.
    private static final Version STEERING_VERSION = Version.parse("2.1.295");
    private static final Duration INPUT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROCESS_CANCELLATION_GRACE = Duration.ofSeconds(3);
    private static final Duration SESSION_CLOSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_STDERR_BYTES = 1024 * 1024;
    private static final int MAX_PROTOCOL_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_LINE_BYTES = 16 * 1024;
    // Auto-mode defaults include the native policy, which exceeds 32 KiB on supported releases.
    private static final int MAX_PROBE_OUTPUT_BYTES = 256 * 1024;
    private static final Set<String> REQUIRED_FLAGS = ImmutableSet.of(
            "--add-dir",
            "--allowedTools",
            "--append-system-prompt",
            "--disable-slash-commands",
            "--include-hook-events",
            "--include-partial-messages",
            "--mcp-config",
            "--model",
            "--no-session-persistence",
            "--output-format",
            "--permission-mode",
            "--permission-prompt-tool",
            "--print",
            "--resume",
            "--session-id",
            "--effort",
            "--settings",
            "--setting-sources",
            "--strict-mcp-config",
            "--tools",
            "--verbose");
    static final String PLAN_INSTRUCTION = """
            For work with multiple steps, use the native TodoWrite tool to create and maintain the task's structured plan.
            Keep todo statuses current as work moves from pending to in_progress to completed. Update the existing plan as the work changes.
            """;

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
    private final AgentLaneId laneId;
    private final String label;
    private final Optional<String> loginEmail;
    private final Path claudeHome;
    private final ProcessSupervisor supervisor;
    private final Map<String, String> environment;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;
    private final PermissionBroker permissions;
    private final Set<ClaudeSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean forwardSubagentText = true;
    private volatile boolean steering;
    private volatile boolean reviewChildAgents;
    private volatile boolean sessionCost = true;
    private volatile boolean closed;

    /**
     * @param claudeHome ByteQuay's own Claude home; every Claude process this driver starts runs with
     * {@code CLAUDE_CONFIG_DIR} set to it, so the user's {@code ~/.claude} never reaches a task
     */
    public ClaudeCliDriver(String executable, ObjectMapper objectMapper, PermissionBroker permissions, ProcessSupervisor supervisor, Path claudeHome)
    {
        this(executable, objectMapper, permissions, supervisor, claudeHome, inheritedEnvironment());
    }

    public ClaudeCliDriver(
            String executable,
            ObjectMapper objectMapper,
            PermissionBroker permissions,
            ProcessSupervisor supervisor,
            Path claudeHome,
            AgentLaneId laneId,
            String label,
            Optional<String> loginEmail)
    {
        this(executable, objectMapper, permissions, supervisor, claudeHome, laneId, label, loginEmail, inheritedEnvironment());
    }

    ClaudeCliDriver(String executable, ObjectMapper objectMapper, PermissionBroker permissions, ProcessSupervisor supervisor, Path claudeHome, Map<String, String> environment)
    {
        this(executable, objectMapper, permissions, supervisor, claudeHome, AgentLaneId.CLAUDE_CLI, "Claude Code", Optional.empty(), environment);
    }

    ClaudeCliDriver(
            String executable,
            ObjectMapper objectMapper,
            PermissionBroker permissions,
            ProcessSupervisor supervisor,
            Path claudeHome,
            AgentLaneId laneId,
            String label,
            Optional<String> loginEmail,
            Map<String, String> environment)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.laneId = requireNonNull(laneId, "laneId is null");
        checkArgument(laneId.usesClaude(), "not a Claude lane");
        this.label = requireNonNull(label, "label is null");
        this.loginEmail = requireNonNull(loginEmail, "loginEmail is null");
        this.claudeHome = requireNonNull(claudeHome, "claudeHome is null").toAbsolutePath().normalize();
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        // Overrides any inherited value: a shell that exports CLAUDE_CONFIG_DIR must not redirect
        // ByteQuay's processes into the user's own Claude setup.
        this.environment = ImmutableMap.<String, String>builder()
                .putAll(Maps.filterKeys(requireNonNull(environment, "environment is null"), name -> !name.equals("CLAUDE_CONFIG_DIR")
                        // Named accounts must use their own login, even when the backend inherited API credentials.
                        && (laneId.equals(AgentLaneId.CLAUDE_CLI) || (!name.startsWith("ANTHROPIC_") && !name.equals("CLAUDE_CODE_OAUTH_TOKEN")))))
                .put("CLAUDE_CONFIG_DIR", this.claudeHome.toString())
                .buildOrThrow();
        this.redactor = SecretRedactor.fromEnvironment(this.environment);
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null").copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.permissions = requireNonNull(permissions, "permissions is null");
    }

    @Override
    public AgentLaneId id()
    {
        return laneId;
    }

    @Override
    public String label()
    {
        return label;
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        try {
            ProviderCli.Result version = run(executable, "--version");
            // get_usage and safe-mode are experimental; keep task support on the existing minimum.
            if (version.exitCode() != 0 || version.truncated()
                    || parseVersion(version.stdout()).filter(value -> value.compareTo(Version.parse("2.1.293")) >= 0).isEmpty()) {
                return ProviderQuotaSnapshot.unavailable("Usage requires Claude Code 2.1.293 or newer.");
            }
            AuthenticationProbe authentication = probeAuthentication();
            if (authentication.readiness() != AgentProviderStatus.Readiness.READY) {
                return ProviderQuotaSnapshot.unavailable(authentication.readiness() == AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED
                        ? "Sign in to this Claude account in Settings to see usage."
                        : "Claude account status is temporarily unavailable. Try again.");
            }
            return ClaudeQuota.read(supervisor, processSpec(
                    new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "claude-usage"),
                    ImmutableList.of(executable, "--print", "--safe-mode", "--setting-sources=", "--settings", "{\"disableAllHooks\":true}",
                            "--tools=", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}", "--no-chrome", "--input-format=stream-json",
                            "--output-format=stream-json", "--verbose", "--no-session-persistence"),
                    claudeHome, Duration.ofMinutes(2), OptionalInt.empty(), MAX_DIAGNOSTIC_LINE_BYTES), objectMapper);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Claude usage query interrupted", failure);
        }
    }

    @Override
    public AgentCapabilities capabilities()
    {
        // Forwarded child messages identify the Agent invocation that owns their execution.
        return new AgentCapabilities(
                true,
                forwardSubagentText,
                steering,
                ImmutableSet.copyOf(AgentPermissionPolicy.values()),
                ImmutableSet.of("text/plain", "text/plain; charset=utf-8"));
    }

    @Override
    public boolean reviewChildAgents()
    {
        return reviewChildAgents;
    }

    @Override
    public AgentProviderStatus probe()
    {
        try {
            ProviderCli.Result versionResult = run(executable, "--version");
            if (versionResult.truncated()) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.empty(), "Claude CLI version output was truncated");
            }
            if (versionResult.exitCode() != 0) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.empty(), versionResult.combinedOutput());
            }
            Optional<Version> parsed = parseVersion(versionResult.stdout());
            if (parsed.isEmpty()) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.empty(), "Unable to read Claude CLI version");
            }
            Version version = parsed.orElseThrow();
            if (version.compareTo(MINIMUM_VERSION) < 0) {
                return status(
                        AgentProviderStatus.Readiness.UNSUPPORTED,
                        Optional.of(version.toString()),
                        "Unsupported Claude CLI version; expected " + MINIMUM_VERSION + " or newer. Run claude update, then check again.");
            }

            ProviderCli.Result help = run(executable, "--help");
            if (help.truncated()) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.of(version.toString()), "Claude CLI help output was truncated");
            }
            if (help.exitCode() != 0) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.of(version.toString()), "claude --help failed: " + help.combinedOutput());
            }
            Optional<String> missing = REQUIRED_FLAGS.stream()
                    .filter(flag -> !help.stdout().contains(flag))
                    .findFirst();
            if (missing.isPresent()) {
                return status(AgentProviderStatus.Readiness.UNSUPPORTED, Optional.of(version.toString()), "Claude CLI does not support " + missing.orElseThrow());
            }
            if (!help.stdout().contains("\"auto\"")) {
                return status(AgentProviderStatus.Readiness.UNSUPPORTED, Optional.of(version.toString()), "Claude CLI does not support auto permission mode");
            }
            forwardSubagentText = version.compareTo(SUBAGENT_TEXT_VERSION) >= 0
                    && help.stdout().contains("--forward-subagent-text");
            // A review child is visible only through forwarded text, and gated only on a release with the verified hook input.
            reviewChildAgents = forwardSubagentText && version.compareTo(REVIEW_CHILD_VERSION) >= 0;
            steering = version.compareTo(STEERING_VERSION) >= 0 && help.stdout().contains("--input-format");
            sessionCost = version.compareTo(SESSION_COST_VERSION) >= 0;
            ProviderCli.Result defaults = run(executable, "auto-mode", "defaults");
            if (defaults.timedOut()) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.of(version.toString()), "Claude auto-mode defaults timed out");
            }
            if (defaults.truncated()) {
                return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.of(version.toString()), "Claude auto-mode defaults output was truncated");
            }
            if (defaults.exitCode() != 0) {
                return status(
                        AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        Optional.of(version.toString()),
                        "claude auto-mode defaults failed: " + defaults.combinedOutput());
            }
            if (!supportsAutoModeHardDeny(defaults.stdout())) {
                return status(AgentProviderStatus.Readiness.UNSUPPORTED, Optional.of(version.toString()), "Claude CLI does not support auto-mode hard-deny rules");
            }
            AuthenticationProbe authentication = probeAuthentication();
            if (authentication.readiness() != AgentProviderStatus.Readiness.READY) {
                return status(authentication.readiness(), Optional.of(version.toString()), authentication.diagnostic());
            }
            return status(
                    forwardSubagentText ? AgentProviderStatus.Readiness.READY : AgentProviderStatus.Readiness.DEGRADED,
                    Optional.of(version.toString()),
                    forwardSubagentText
                            ? authentication.diagnostic()
                            : authentication.diagnostic() + "; subagent transcript forwarding is unavailable");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.empty(), "Claude CLI probe was interrupted");
        }
        catch (RuntimeException e) {
            AgentProviderStatus.Readiness readiness = e.getCause() instanceof IOException
                    ? AgentProviderStatus.Readiness.CLI_MISSING
                    : AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
            return status(readiness, Optional.empty(), "Claude CLI is not available: " + e.getMessage());
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
            if (!reference.laneId().equals(laneId) || reference.contractVersion() != CONTRACT_VERSION) {
                throw new AgentUnavailableException("Unsupported Claude session reference");
            }
        }
        String sessionId = existing.map(AgentSessionRef::providerValue)
                .orElseGet(() -> UUID.randomUUID().toString());
        ClaudeSession session = new ClaudeSession(new AgentSessionRef(laneId, CONTRACT_VERSION, sessionId), context, events, existing.isPresent());
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
            McpRegistration registration,
            AgentPermissionScope permissionScope,
            Optional<String> model,
            Optional<String> effort,
            String managedInstructions,
            String sessionId,
            boolean resume,
            List<AgentAttachment> attachments,
            boolean streamingInput,
            boolean asksUser)
    {
        ImmutableList.Builder<String> argv = ImmutableList.builder();
        ImmutableList.Builder<String> allowedTools = ImmutableList.builder();
        argv.add(executable, "--print", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--include-hook-events");
        if (streamingInput) {
            argv.add("--input-format", "stream-json");
        }
        if (forwardSubagentText) {
            argv.add("--forward-subagent-text");
        }
        model.ifPresent(value -> argv.add("--model", value));
        effort.map(ClaudeCliDriver::effortArgument)
                .ifPresent(value -> argv.add("--effort", value));
        argv.add("--strict-mcp-config", "--setting-sources=", "--settings", registration.settingsFile().orElseThrow().toString());
        if (permissionScope.access() == SessionAccess.TEXT_ONLY) {
            argv.add("--tools=", "--disable-slash-commands", "--no-session-persistence");
        }
        else if (permissionScope.access() == SessionAccess.TOOLS_ONLY) {
            // Only its ByteQuay tools. Session persistence stays on: the day's conversation resumes from the same folder.
            argv.add("--tools=", "--disable-slash-commands");
        }
        else if (permissionScope.access() == SessionAccess.READ_ONLY || permissionScope.access() == SessionAccess.REVIEW_READ_ONLY) {
            // A read-only session reads the checkout and never edits it. Session persistence
            // stays on: a discussion resumes its own conversation for every later question.
            argv.add("--disable-slash-commands", "--tools", "Bash,Glob,Grep,Read" + (asksUser ? ",AskUserQuestion" : ""));
        }
        else if (permissionScope.review()) {
            // Keep new provider workflow/delegation tools out of review sessions. Agent starts only the read-only Explore
            // children the broker's gate admits, in an attended review on a release where that gate was verified.
            argv.add("--disable-slash-commands", "--tools", "Bash,Glob,Grep,Read,Edit,Write,NotebookEdit,WebFetch,WebSearch,TodoWrite,TaskOutput,TaskStop"
                    + (reviewChildAgents && !permissionScope.unattended() ? ",Agent" : ""));
        }
        if (permissionScope.access() == SessionAccess.TOOLS_ONLY || (registration.toolNames().contains(RelatedWorkspaceBridge.TOOL_NAME) && !reviewChildAgents)) {
            // Older releases lack verified child identity in hooks; keep the related-work tool with the only agent.
            // A tools-only session starts no children at all: one would inherit its root-only tools.
            argv.add("--disallowedTools", "Agent,Task,Skill,Workflow,SendMessage");
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
        registration.toolNames().forEach(name -> allowedTools.add("mcp__bytequay__" + name));
        String instruction = ProviderInstructions.compose(
                permissionScope, PLAN_INSTRUCTION, managedInstructions, registration.instruction().orElse(""), PermissionBroker.sandboxInstruction(permissionScope));
        if (instruction.isEmpty()) {
            argv.add("--append-system-prompt=");
        }
        else {
            argv.add("--append-system-prompt", instruction);
        }
        List<String> allowed = allowedTools.build();
        if (!allowed.isEmpty()) {
            argv.add("--allowedTools", allowed.stream().collect(joining(",")));
        }
        argv.add(
                "--permission-mode", "auto",
                "--mcp-config", registration.configFile().orElseThrow().toString(),
                "--permission-prompt-tool", "mcp__bytequay__permission_prompt",
                resume ? "--resume" : "--session-id", sessionId);
        return argv.build();
    }

    private static ProcessOwner.Kind ownerKind(AgentSessionContext context)
    {
        return switch (context.access()) {
            // A scratch or assistant folder is no task's worktree, so startup recovery has no task to hold for it.
            case TEXT_ONLY, TOOLS_ONLY -> ProcessOwner.Kind.CLI_PROBE;
            case READ_ONLY -> context.scratchWorktree() ? ProcessOwner.Kind.CLI_PROBE : ProcessOwner.Kind.DISCUSSION;
            case FULL, REVIEW_READ_ONLY -> ProcessOwner.Kind.CLAUDE_TURN;
        };
    }

    private static String effortArgument(String effort)
    {
        String normalized = effort.toLowerCase(Locale.ROOT);
        return normalized.equals("extra") ? "xhigh" : normalized;
    }

    private AgentProviderStatus status(AgentProviderStatus.Readiness readiness, Optional<String> version, String diagnostic)
    {
        String message = diagnostic == null || diagnostic.isBlank() ? readiness.name().toLowerCase() : diagnostic;
        String command = executable.matches("[A-Za-z0-9_./-]+") ? executable : shellQuote(executable);
        // A login shell can export credentials again; named-profile sign-in must match the driver's environment.
        String loginEnvironment = laneId.equals(AgentLaneId.CLAUDE_CLI) ? ""
                : "/usr/bin/env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL -u CLAUDE_CODE_OAUTH_TOKEN ";
        return new AgentProviderStatus(
                laneId,
                readiness,
                version,
                redactor.redact(message),
                capabilities(),
                readiness == AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED
                        ? Optional.of(loginEnvironment + "CLAUDE_CONFIG_DIR=" + shellQuote(claudeHome.toString()) + " " + command + " auth login"
                                + (laneId.equals(AgentLaneId.CLAUDE_CLI) ? "" : " --claudeai")
                                + loginEmail.map(email -> " --email " + shellQuote(email)).orElse(""))
                        : Optional.empty());
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** The release that {@code claude --version} reports, such as {@code 2.1.278 (Claude Code)}. */
    static Optional<Version> parseVersion(String output)
    {
        String first = output.strip().split("\\s+", 2)[0];
        return RELEASE.matcher(first).matches() ? Optional.of(Version.parse(first)) : Optional.empty();
    }

    private AuthenticationProbe probeAuthentication()
            throws InterruptedException
    {
        ProviderCli.Result result = run(executable, "auth", "status", "--json");
        if (result.timedOut()) {
            return AuthenticationProbe.unavailable("Claude authentication status timed out");
        }
        if (result.truncated()) {
            return AuthenticationProbe.unavailable("Claude authentication status was truncated");
        }
        Optional<JsonNode> status = authenticationStatus(result.stdout());
        if (status.isEmpty()) {
            return AuthenticationProbe.unavailable("Claude returned an invalid authentication status");
        }
        if (!status.orElseThrow().path("loggedIn").booleanValue()) {
            return new AuthenticationProbe(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, loginEmail.map(email -> "Sign in as " + email).orElse("Claude sign-in required"));
        }
        if (result.exitCode() != 0) {
            return AuthenticationProbe.unavailable("Claude authentication status failed");
        }
        Optional<String> authenticatedEmail = Optional.ofNullable(status.orElseThrow().get("email"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .filter(email -> !email.isBlank() && email.length() <= 320 && email.chars().noneMatch(Character::isISOControl));
        if (loginEmail.isPresent() && authenticatedEmail.filter(email -> email.equalsIgnoreCase(loginEmail.orElseThrow())).isEmpty()) {
            return new AuthenticationProbe(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                    authenticatedEmail.map(email -> "Signed in as " + email + "; sign in as " + loginEmail.orElseThrow())
                            .orElse("Claude did not report a login email; sign in as " + loginEmail.orElseThrow()));
        }
        return new AuthenticationProbe(AgentProviderStatus.Readiness.READY, authenticatedEmail.map(email -> "Signed in as " + email).orElse("Signed in"));
    }

    private Optional<JsonNode> authenticationStatus(String stdout)
    {
        if (stdout == null || stdout.isBlank()) {
            return Optional.empty();
        }
        try (var parser = objectMapper.createParser(stdout)) {
            JsonNode status = objectMapper.readTree(parser);
            if (status == null || !status.isObject() || parser.nextToken() != null) {
                return Optional.empty();
            }
            JsonNode loggedIn = status.get("loggedIn");
            return loggedIn != null && loggedIn.isBoolean() ? Optional.of(status) : Optional.empty();
        }
        catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private boolean supportsAutoModeHardDeny(String stdout)
    {
        try (var parser = objectMapper.createParser(stdout)) {
            JsonNode defaults = objectMapper.readTree(parser);
            if (defaults == null || !defaults.isObject() || parser.nextToken() != null || !defaults.path("hard_deny").isArray()) {
                return false;
            }
            for (JsonNode rule : defaults.path("hard_deny")) {
                if (!rule.isTextual()) {
                    return false;
                }
            }
            return true;
        }
        catch (IOException ignored) {
            return false;
        }
    }

    private ProviderCli.Result run(String... command)
            throws InterruptedException
    {
        // Even auth status can rotate a token and exit before saving it. Read-only probes must never reach the network.
        return run(PROBE_TIMEOUT, ImmutableList.<String>builder()
                .add("/usr/bin/sandbox-exec", "-p", "(version 1) (allow default) (deny network*)")
                .add(command)
                .build().toArray(String[]::new));
    }

    private ProviderCli.Result run(Duration maximumRuntime, String... command)
            throws InterruptedException
    {
        return ProviderCli.run(supervisor, processSpec(
                new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "claude-cli"),
                ImmutableList.copyOf(command),
                Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                maximumRuntime,
                OptionalInt.of(MAX_PROBE_OUTPUT_BYTES),
                MAX_PROBE_OUTPUT_BYTES), "Claude CLI");
    }

    private ProcessSpec processSpec(ProcessOwner owner, List<String> argv, Path workingDirectory, Duration maximumRuntime, OptionalInt stdoutCaptureLimit, int maximumStderrBytes)
    {
        return new ProcessSpec(
                owner,
                argv,
                workingDirectory,
                GitCliEnvironment.withoutCredentials(environment),
                stdoutCaptureLimit,
                maximumStderrBytes,
                maximumRuntime,
                PROCESS_CANCELLATION_GRACE);
    }

    private static Map<String, String> inheritedEnvironment()
    {
        return inheritedEnvironment(System.getenv(), Optional.ofNullable(System.getProperty("java.home")));
    }

    static Map<String, String> inheritedEnvironment(Map<String, String> processEnvironment, Optional<String> javaHome)
    {
        ImmutableMap.Builder<String, String> environment = ImmutableMap.<String, String>builder()
                .putAll(ProviderCli.inheritedEnvironment(processEnvironment, INHERITED_ENVIRONMENT));
        // Claude Code's macOS sandbox limits sysctl reads, which breaks /usr/libexec/java_home and the /usr/bin/java stub.
        Stream.of(Optional.ofNullable(processEnvironment.get("JAVA_HOME")), javaHome)
                .flatMap(Optional::stream)
                .filter(ClaudeCliDriver::hasJava)
                .findFirst()
                .ifPresent(home -> environment.put("JAVA_HOME", home));
        return environment.buildOrThrow();
    }

    private static boolean hasJava(String home)
    {
        Path java = Path.of(home, "bin", "java");
        return java.isAbsolute() && Files.isRegularFile(java) && Files.isExecutable(java);
    }

    private record AuthenticationProbe(AgentProviderStatus.Readiness readiness, String diagnostic)
    {
        private AuthenticationProbe
        {
            requireNonNull(readiness, "readiness is null");
            requireNonNull(diagnostic, "diagnostic is null");
        }

        private static AuthenticationProbe unavailable(String diagnostic)
        {
            return new AuthenticationProbe(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, diagnostic);
        }
    }

    private final class ClaudeSession
            implements AgentSession
    {
        private final AgentSessionRef reference;
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final McpRegistration registration;
        private final AgentPermissionScope permissionScope;
        private final String setup;
        private final SecretRedactor redactor;
        private ClaudeRunningTurn running;
        private ClaudeRunningTurn lastTurn;
        private boolean resume;
        private boolean sessionClosed;

        private ClaudeSession(AgentSessionRef reference, AgentSessionContext context, AgentEventSink events, boolean resume)
        {
            this.reference = requireNonNull(reference, "reference is null");
            this.context = requireNonNull(context, "context is null");
            this.events = requireNonNull(events, "events is null");
            this.registration = permissions.register(
                    context.taskId(),
                    context.worktree(),
                    context.runtimeDirectory(),
                    context.providerDirectory(),
                    context.permissionPolicy(),
                    context.access(),
                    this::requestApproval,
                    ClaudeCliDriver.this.redactor,
                    context.codeQueries(),
                    environment,
                    context.discussionContext(), context.sessionTools());
            this.permissionScope = new AgentPermissionScope(context.permissionPolicy(), context.access(), registration.review().isPresent());
            // ByteQuay's own part of the instructions every turn's launch arguments carry; see argv.
            this.setup = ProviderInstructions.setup(permissionScope, PLAN_INSTRUCTION, Optional.of(registration.toolsDigest()),
                    registration.instruction().orElse(""), PermissionBroker.sandboxInstruction(permissionScope));
            this.redactor = ClaudeCliDriver.this.redactor.withSecret(registration.token());
            this.resume = resume;
        }

        private CompletionStage<ProviderApprovalDecision> requestApproval(ProviderApprovalRequest request, Duration timeout)
        {
            ClaudeRunningTurn expected;
            synchronized (this) {
                expected = running;
                if (sessionClosed || expected == null || expected.completion.isDone()) {
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                }
            }
            return context.approvals().request(request, timeout)
                    .thenApply(decision -> {
                        synchronized (this) {
                            if (sessionClosed || running != expected || expected.completion.isDone()) {
                                return ProviderApprovalDecision.deny();
                            }
                            if (decision.timedOut()) {
                                expected.interruptionReason = "Claude approval timed out";
                                expected.cancel();
                            }
                            return decision;
                        }
                    });
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
            lastTurn = null;
            running = null;
            running = new ClaudeRunningTurn(this, command, resume);
            return running;
        }

        /** Retains a process that started even when writing its prompt fails before start returns. */
        private synchronized void started(ClaudeRunningTurn turn)
        {
            running = requireNonNull(turn, "turn is null");
        }

        private synchronized void markResumable()
        {
            resume = true;
        }

        private synchronized void finished(ClaudeRunningTurn turn)
        {
            if (running == turn) {
                running = null;
                lastTurn = turn;
            }
        }

        @Override
        public void close()
        {
            ClaudeRunningTurn active;
            ClaudeRunningTurn owned;
            synchronized (this) {
                sessionClosed = true;
                active = running;
                owned = active == null ? lastTurn : active;
            }

            RuntimeException failure = null;
            ProcessExit.TreeTermination termination = null;
            if (owned != null) {
                try {
                    termination = owned.stopAndVerify().toCompletableFuture()
                            .get(SESSION_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    if (active != null) {
                        active.completion().toCompletableFuture()
                                .get(SESSION_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = new AgentUnavailableException("Interrupted while stopping Claude process", e);
                }
                catch (ExecutionException e) {
                    failure = new AgentUnavailableException("Claude process did not report its exit", e.getCause());
                }
                catch (TimeoutException e) {
                    failure = new AgentUnavailableException(
                            "Claude process did not stop within " + SESSION_CLOSE_TIMEOUT.toSeconds()
                                    + " seconds, so its process tree is not verified stopped",
                            e);
                }
            }
            if (failure == null
                    && termination != null
                    && termination != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                failure = new AgentUnavailableException("Claude process tree could not be verified stopped");
            }
            try {
                registration.close();
            }
            catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
                else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
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
        private final ClaudeEventMapper mapper;
        private final boolean completeSubagentStream;
        private final Utf8LineBuffer stdout = new Utf8LineBuffer(MAX_PROTOCOL_LINE_BYTES, false, this::receive);
        private final Utf8LineBuffer stderr = new Utf8LineBuffer(MAX_DIAGNOSTIC_LINE_BYTES, false, this::diagnostic);
        private final StringBuilder lastDiagnostic = new StringBuilder();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final boolean streamingInput;
        private final Object inputLock = new Object();
        private final Map<String, CompletableFuture<Void>> pendingInputs = new HashMap<>();
        private boolean inputClosed;
        private volatile ManagedProcess process;
        private volatile ClaudeProtocol.ResultMessage result;
        private volatile String interruptionReason;
        private volatile String protocolFailure;
        private volatile boolean stdoutTruncated;
        private boolean streamUncertain;

        private ClaudeRunningTurn(ClaudeSession session, AgentTurnCommand command, boolean resume)
        {
            this.session = requireNonNull(session, "session is null");
            this.mapper = new ClaudeEventMapper(session.redactor, session.registration.development()
                    .filter(bridge -> bridge.turnId().equals(command.turnId())), sessionCost);
            this.reference = new ProviderTurnRef(command.turnId());
            streamingInput = steering && !session.context.restricted();
            List<AgentAttachment> attachments = attachments(session.context, command.attachments());
            ProcessSpec spec = processSpec(
                    new ProcessOwner(ownerKind(session.context), session.context.restricted() && session.context.access() != SessionAccess.REVIEW_READ_ONLY ? session.context.taskId() : command.turnId()),
                    argv(
                            session.registration,
                            session.permissionScope,
                            command.model().or(session.context::model),
                            command.effort().or(session.context::effort),
                            session.context.managedInstructions(),
                            session.reference.providerValue(),
                            resume,
                            attachments,
                            streamingInput,
                            session.context.mayAskUser(session.permissionScope.review())),
                    session.context.worktree(),
                    command.maximumRuntime(),
                    OptionalInt.empty(),
                    MAX_STDERR_BYTES);
            completeSubagentStream = spec.argv().contains("--forward-subagent-text");
            ManagedProcess started = supervisor.prepare(spec).start(this);
            this.process = started;
            session.started(this);
            if (cancelled.get()) {
                started.cancel();
            }
            started.exit().whenComplete(this::processExited);
            String inputId = UUID.randomUUID().toString();
            String text = prompt(command.message(), attachments);
            ByteBuffer prompt = streamingInput ? streamInput(inputId, text, "next") : StandardCharsets.UTF_8.encode(text);
            if (streamingInput) {
                synchronized (inputLock) {
                    pendingInputs.put(inputId, new CompletableFuture<>());
                }
            }
            try {
                started.write(prompt).toCompletableFuture().join();
            }
            catch (RuntimeException failure) {
                failPromptDelivery(started, "Unable to write Claude prompt", failure);
            }
            if (!streamingInput) {
                try {
                    started.closeInput();
                }
                catch (RuntimeException failure) {
                    failPromptDelivery(started, "Unable to close Claude prompt input", failure);
                }
            }
        }

        private ByteBuffer streamInput(String id, String text, String priority)
        {
            var message = objectMapper.createObjectNode();
            message.put("type", "user").put("uuid", id).put("session_id", session.reference.providerValue()).put("priority", priority).putNull("parent_tool_use_id");
            message.putObject("message").put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
            return StandardCharsets.UTF_8.encode(message + "\n");
        }

        @Override
        public void steer(AgentTurnInput input)
        {
            requireNonNull(input, "input is null");
            String id = UUID.randomUUID().toString();
            ByteBuffer message = streamInput(id, prompt(input.message(), attachments(session.context, input.attachments())), "now");
            CompletableFuture<Void> receipt = new CompletableFuture<>();
            synchronized (inputLock) {
                if (!streamingInput || inputClosed || completion.isDone() || cancelled.get()) {
                    throw new AgentUnavailableException("Claude turn is not accepting steering input");
                }
                pendingInputs.put(id, receipt);
            }
            try {
                process.write(message).toCompletableFuture().get(INPUT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                interruptionReason = "Claude steering write was interrupted";
                cancelProcess();
                throw new AgentDeliveryUncertainException("Claude steering delivery was interrupted", failure);
            }
            catch (ExecutionException | TimeoutException | RuntimeException failure) {
                interruptionReason = "Claude steering write did not complete";
                cancelProcess();
                throw new AgentDeliveryUncertainException("Unable to confirm Claude steering delivery", failure);
            }
            try {
                receipt.get(INPUT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AgentDeliveryUncertainException("Claude steering acknowledgement was interrupted", failure);
            }
            catch (ExecutionException | TimeoutException failure) {
                throw new AgentDeliveryUncertainException("Claude did not acknowledge steering input", failure);
            }
        }

        private void observeInput(ClaudeProtocol.Message message)
        {
            if (!streamingInput) {
                return;
            }
            boolean closeInput = false;
            boolean cancel = false;
            synchronized (inputLock) {
                if (message instanceof ClaudeProtocol.CommandLifecycleMessage lifecycle) {
                    CompletableFuture<Void> receipt = pendingInputs.get(lifecycle.commandId());
                    if (receipt != null) {
                        switch (lifecycle.state()) {
                            case "queued", "started" -> receipt.complete(null);
                            case "completed" -> {
                                receipt.complete(null);
                                pendingInputs.remove(lifecycle.commandId());
                            }
                            case "cancelled" -> {
                                interruptionReason = "Claude cancelled submitted input before completing it";
                                receipt.completeExceptionally(new AgentUnavailableException(interruptionReason));
                                cancel = true;
                            }
                            default -> {}
                        }
                    }
                }
                // A result can precede a queued steer, even with queued_turn_count=0. Wait for every input's own completion.
                if (result != null && pendingInputs.isEmpty() && !inputClosed) {
                    inputClosed = true;
                    closeInput = true;
                }
            }
            if (cancel) {
                cancelProcess();
            }
            if (closeInput) {
                try {
                    process.closeInput();
                }
                catch (RuntimeException failure) {
                    interruptionReason = session.redactor.redact("Unable to close Claude prompt input: " + failureMessage(failure));
                    cancelProcess();
                }
            }
        }

        private void failPromptDelivery(ManagedProcess process, String message, RuntimeException failure)
        {
            interruptionReason = session.redactor.redact(message + ": " + failureMessage(failure));
            try {
                process.cancel();
            }
            catch (RuntimeException cancellationFailure) {
                if (failure != cancellationFailure) {
                    failure.addSuppressed(cancellationFailure);
                }
            }
            throw new AgentUnavailableException(interruptionReason, failure);
        }

        @Override
        public ProviderTurnRef reference()
        {
            return reference;
        }

        @Override
        public Optional<String> setup()
        {
            return Optional.of(session.setup);
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

        private CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancelled.set(true);
            ManagedProcess current = process;
            if (current == null) {
                return CompletableFuture.failedFuture(new AgentUnavailableException("Claude process was not retained for shutdown verification"));
            }
            return current.stopAndVerify();
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
            ClaudeProtocol.Message message = null;
            try {
                message = ClaudeProtocol.decode(objectMapper, line);
                if (message.sessionId().isPresent()
                        && !message.sessionId().orElseThrow().equals(session.reference.providerValue())) {
                    protocolFailure = "Claude returned a different session ID";
                    cancelProcess();
                    return;
                }
                if (!(message instanceof ClaudeProtocol.CommandLifecycleMessage)) {
                    message.sessionId().ifPresent(ignored -> session.markResumable());
                }
                mapper.map(message).forEach(session.events::emit);
                if (message instanceof ClaudeProtocol.ResultMessage terminal) {
                    result = terminal;
                }
                observeInput(message);
            }
            catch (RuntimeException failure) {
                streamUncertain = true;
                if (message instanceof ClaudeProtocol.ResultMessage) {
                    interruptionReason = "Unable to persist Claude terminal events: " + failureMessage(failure);
                    if (streamingInput) {
                        cancelProcess();
                    }
                }
                session.events.emit(new ProviderAgentEvent.Error(Optional.empty(), "claude_stream", session.redactor.redact(failureMessage(failure)), true));
            }
        }

        private void diagnostic(String line)
        {
            String value = session.redactor.redact(line);
            log.debug("Claude CLI diagnostics for task {} turn {}: {}", session.context.taskId(), reference.value(), value);
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
            synchronized (inputLock) {
                inputClosed = true;
                pendingInputs.values().forEach(receipt -> receipt.completeExceptionally(new AgentUnavailableException("Claude process exited before acknowledging input")));
                if (streamingInput && !pendingInputs.isEmpty() && !cancelled.get() && interruptionReason == null
                        && exit != null && exit.reason() == ProcessExit.Reason.EXITED) {
                    interruptionReason = "Claude exited before completing all submitted input";
                }
            }
            if (failure != null) {
                interruptionReason = "Claude process failed: " + failureMessage(failure);
            }
            boolean finishedWork = failure == null
                    && completeSubagentStream
                    && interruptionReason == null
                    && protocolFailure == null
                    && !streamUncertain
                    && !stdoutTruncated
                    && exit != null
                    && !exit.stdoutTruncated()
                    && exit.reason() == ProcessExit.Reason.EXITED
                    && mapper.hasFinishedWork();
            if (finishedWork && exit.treeTermination() != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                // EOF includes notifications that may follow the root result. Only the complete
                // provider lifecycle can resolve an old process-observation gap; a final answer cannot.
                try {
                    process.stopAfterWorkFinished().whenComplete((termination, cleanupFailure) -> {
                        if (cleanupFailure != null || termination != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                            log.warn("Claude work finished but runtime cleanup remains unverified for task {} turn {}",
                                    session.context.taskId(), reference.value(), cleanupFailure);
                        }
                        completeExited(exit, termination == ProcessExit.TreeTermination.VERIFIED_STOPPED);
                    });
                }
                catch (RuntimeException cleanupFailure) {
                    log.warn("Unable to record Claude completion for task {} turn {}", session.context.taskId(), reference.value(), cleanupFailure);
                    completeExited(exit, false);
                }
                return;
            }
            if (interruptionReason == null
                    && exit != null
                    && exit.reason() == ProcessExit.Reason.EXITED
                    && exit.treeTermination() == ProcessExit.TreeTermination.UNVERIFIABLE) {
                interruptionReason = "Claude process tree could not be verified stopped";
            }
            completeExited(exit, exit != null && exit.treeTermination() == ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }

        private void completeExited(ProcessExit exit, boolean providerQuiescent)
        {
            Optional<String> scratchCleanupFailure = Optional.empty();
            if (providerQuiescent) {
                try {
                    removeEmptyClaudeScratch(session.context.worktree());
                }
                catch (IOException | RuntimeException cleanupFailure) {
                    log.warn("Unable to remove Claude scratch directory for task {} turn {}", session.context.taskId(), reference.value(), cleanupFailure);
                    scratchCleanupFailure = Optional.of(
                            "ByteQuay could not remove Claude's empty .claude/.cc-writes scratch directory; "
                                    + "check the worktree permissions before retrying. The worktree was retained");
                }
            }

            AgentTurnResult turnResult;
            if (scratchCleanupFailure.isPresent()) {
                turnResult = failedResult(AgentTurnResult.State.FAILED, scratchCleanupFailure.orElseThrow(), exit.duration(), providerQuiescent);
            }
            else if (interruptionReason != null) {
                turnResult = failedResult(
                        AgentTurnResult.State.INTERRUPTED,
                        session.redactor.redact(interruptionReason),
                        exit == null ? Duration.between(startedAt, Instant.now()) : exit.duration(),
                        providerQuiescent);
            }
            else if (protocolFailure != null) {
                turnResult = failedResult(AgentTurnResult.State.FAILED, protocolFailure, exit.duration(), providerQuiescent);
            }
            else if (exit.reason() == ProcessExit.Reason.TIMED_OUT) {
                turnResult = failedResult(AgentTurnResult.State.INTERRUPTED, "Claude turn timed out", exit.duration(), providerQuiescent);
            }
            else if (exit.reason() == ProcessExit.Reason.CANCELLED) {
                turnResult = failedResult(AgentTurnResult.State.CANCELLED, "Claude turn was cancelled", exit.duration(), providerQuiescent);
            }
            else if (stdoutTruncated || exit.stdoutTruncated()) {
                turnResult = failedResult(AgentTurnResult.State.FAILED, "Claude protocol output was truncated", exit.duration(), providerQuiescent);
            }
            else if (result == null) {
                turnResult = failedResult(AgentTurnResult.State.FAILED, exitReason(exit), exit.duration(), providerQuiescent);
            }
            else if (!result.successful() || exit.exitCode() != 0) {
                turnResult = failedResult(AgentTurnResult.State.FAILED, session.redactor.redact(result.errorMessage()), Duration.ofMillis(result.durationMs()), providerQuiescent);
            }
            else {
                turnResult = new AgentTurnResult(
                        AgentTurnResult.State.COMPLETED,
                        result.result()
                                .filter(value -> !value.isBlank())
                                .map(session.redactor::redact),
                        Optional.empty(),
                        Duration.ofMillis(result.durationMs()),
                        providerQuiescent);
            }
            if (scratchCleanupFailure.isEmpty()) {
                turnResult = classifyAuthenticationFailure(turnResult);
            }
            turnResult = emitFailureEvent(turnResult);
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
                session.events.emit(new ProviderAgentEvent.Lifecycle(result == null ? Optional.empty() : result.uuid().map(value -> value + ":completed"), phase, message));
            }
            catch (RuntimeException eventFailure) {
                turnResult = failedResult(
                        AgentTurnResult.State.INTERRUPTED,
                        "Unable to persist Claude completion: " + failureMessage(eventFailure),
                        exit == null ? Duration.between(startedAt, Instant.now()) : exit.duration(),
                        providerQuiescent);
            }
            if (providerQuiescent) {
                acknowledgeVerifiedStop();
            }
            completion.complete(turnResult);
            session.finished(this);
        }

        /**
         * Drops the launch receipt once its tree is verified stopped. It has nothing left to prove:
         * after a crash, startup interrupts a still-open turn and releases its claim without it, so
         * keeping it would only replay it at the next restart.
         */
        private void acknowledgeVerifiedStop()
        {
            try {
                supervisor.acknowledge(process.launchId());
            }
            catch (RuntimeException failure) {
                // The stop is still verified; the next startup replays the kept receipt instead.
                log.warn("Unable to acknowledge Claude launch for task {} turn {}", session.context.taskId(), reference.value(), failure);
            }
        }

        private AgentTurnResult classifyAuthenticationFailure(AgentTurnResult turnResult)
        {
            if (turnResult.state() != AgentTurnResult.State.FAILED || cancelled.get()) {
                return turnResult;
            }
            AuthenticationProbe authentication;
            try {
                authentication = probeAuthentication();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return turnResult;
            }
            catch (RuntimeException unavailable) {
                return turnResult;
            }
            if (authentication.readiness() != AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED) {
                return turnResult;
            }

            String diagnostic = session.redactor.redact(turnResult.terminalReason().orElse("Claude turn failed"));
            log.warn("Claude authentication required for task {} turn {}: {}", session.context.taskId(), reference.value(), diagnostic);
            return new AgentTurnResult(
                    AgentTurnResult.State.FAILED,
                    Optional.empty(),
                    Optional.of("Claude sign-in required"),
                    turnResult.elapsed(),
                    turnResult.providerQuiescent(),
                    Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED));
        }

        private AgentTurnResult emitFailureEvent(AgentTurnResult turnResult)
        {
            if (turnResult.state() != AgentTurnResult.State.FAILED) {
                return turnResult;
            }
            boolean authenticationRequired = turnResult.failureKind()
                    .filter(AgentTurnFailureKind.AUTHENTICATION_REQUIRED::equals)
                    .isPresent();
            try {
                session.events.emit(new ProviderAgentEvent.Error(
                        result == null
                                ? Optional.empty()
                                : result.uuid().map(value -> value + (authenticationRequired
                                        ? ":authentication-required"
                                        : ":error")),
                        authenticationRequired ? "AUTHENTICATION_REQUIRED" : "claude_result",
                        authenticationRequired
                                ? "Claude sign-in required"
                                : session.redactor.redact(turnResult.terminalReason().orElse("Claude turn failed")),
                        false));
                return turnResult;
            }
            catch (RuntimeException eventFailure) {
                return failedResult(
                        AgentTurnResult.State.INTERRUPTED,
                        "Unable to persist Claude terminal failure: " + failureMessage(eventFailure),
                        turnResult.elapsed(),
                        turnResult.providerQuiescent());
            }
        }

        private AgentTurnResult failedResult(AgentTurnResult.State state, String reason, Duration duration, boolean providerQuiescent)
        {
            return new AgentTurnResult(state, Optional.empty(), Optional.of(reason), duration, providerQuiescent);
        }

        private String exitReason(ProcessExit exit)
        {
            synchronized (lastDiagnostic) {
                return lastDiagnostic.isEmpty() ? "Claude exited with code " + exit.exitCode() + " without a terminal result" : lastDiagnostic.toString();
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

    private static void removeEmptyClaudeScratch(Path worktree)
            throws IOException
    {
        Path claudeDirectory = worktree.toAbsolutePath().normalize().resolve(".claude");
        if (!isNonSymbolicDirectory(claudeDirectory)) {
            return;
        }

        Path scratchDirectory = claudeDirectory.resolve(".cc-writes");
        if (isEmptyDirectory(scratchDirectory)) {
            Files.delete(scratchDirectory);
        }
        if (isEmptyDirectory(claudeDirectory)) {
            Files.delete(claudeDirectory);
        }
    }

    private static boolean isEmptyDirectory(Path path)
            throws IOException
    {
        if (!isNonSymbolicDirectory(path)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            return !entries.iterator().hasNext();
        }
    }

    private static boolean isNonSymbolicDirectory(Path path)
            throws IOException
    {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory() && !attributes.isSymbolicLink();
        }
        catch (NoSuchFileException ignored) {
            return false;
        }
    }

    private static List<AgentAttachment> attachments(AgentSessionContext context, List<AgentAttachment> requested)
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
                    throw new AgentUnavailableException("Claude does not support attachment type " + attachment.mediaType());
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
}
