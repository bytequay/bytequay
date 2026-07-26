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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.StreamJsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs one review turn through a CLI agent ({@code claude -p} or
 * {@code codex exec}) and returns its assembled text plus the provider
 * session id, so the next phase can {@code --resume} the same session.
 *
 * <p>This mirrors how {@code AbstractCliThreadAgent} drives a turn — a
 * fresh subprocess per turn, JSONL stdout parsed by the same
 * {@link CliStreamParser}s — but stripped to what a read-only reviewer
 * needs (no MCP permission tool, no thread persistence). The provider
 * keeps cross-phase context via its own session, resumed by id.
 *
 * <p>The argv assembly ({@link #buildArgv}) and the stdout assembly
 * ({@link #assemble}) are pure and unit-tested; the subprocess glue in
 * {@link #run} is the thin, environment-dependent part.
 */
@Component
public class CliReviewRunner
{
    private static final Logger log = LoggerFactory.getLogger(CliReviewRunner.class);

    /** Default per-turn wall-clock ceiling — matches the API reviewer's
     *  streaming timeout so a wedged CLI can't hang the pass. */
    private static final long DEFAULT_TIMEOUT_MS = 6 * 60 * 1000;

    /** Cap on concurrent CLI review subprocesses so a many-seat panel
     *  can't spawn an unbounded number of agents at once (the panel fans
     *  the INDEPENDENT phase out in parallel). Mirrors the spirit of the
     *  scheduler's small CLI lane. */
    private static final int MAX_CONCURRENT = 3;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    /** The local sidecar's base URL — the CLI subprocess reaches the
     *  review MCP server here. Matches server.port in application.properties. */
    private static final String SIDECAR_BASE_URL = "http://127.0.0.1:53123";

    private final ObjectMapper mapper;
    private final long timeoutMs = DEFAULT_TIMEOUT_MS;

    CliReviewRunner(ObjectMapper mapper)
    {
        this.mapper = mapper;
    }

    /** The CLI agents that can hold a reviewer seat. */
    public enum Provider
    {
        CLAUDE("claude-cli", "Claude CLI", "claude"),
        CODEX("codex-cli", "Codex CLI", "codex");

        private final String providerId;
        private final String displayName;
        private final String binary;

        Provider(String providerId, String displayName, String binary)
        {
            this.providerId = providerId;
            this.displayName = displayName;
            this.binary = binary;
        }

        public String providerId()
        {
            return providerId;
        }

        public String displayName()
        {
            return displayName;
        }

        public String binary()
        {
            return binary;
        }

        /** Whether {@code providerId} names a CLI reviewer (vs an API one). */
        public static boolean isCliProvider(String providerId)
        {
            return CLAUDE.providerId.equals(providerId) || CODEX.providerId.equals(providerId);
        }

        public static Provider of(String providerId)
        {
            for (Provider provider : values()) {
                if (provider.providerId.equals(providerId)) {
                    return provider;
                }
            }
            throw new IllegalArgumentException("not a CLI reviewer provider: " + providerId);
        }
    }

    /** Outcome of one CLI review turn. {@code sessionId} is null when the
     *  provider didn't announce one (then the next turn starts fresh). */
    public record Result(
            String text, String sessionId, long costUsdMilli,
            String end, String errorMessage)
    {
        public Result(String text, String sessionId, long costUsdMilli)
        {
            this(text, sessionId, costUsdMilli, "COMPLETED", null);
        }
    }

    /** Points a Claude CLI seat at its review MCP server (the pass + seat
     *  it serves). Null disables the bridge — the seat then has no review
     *  tools and reviews from the inlined diff only (Codex today). */
    public record McpEndpoint(String passId, String participantId, String explicitUrl)
    {
        public McpEndpoint(String passId, String participantId)
        {
            this(passId, participantId, null);
        }
    }

    /**
     * Run one review turn. {@code resumeSessionId} continues a prior
     * session (null/blank starts fresh); {@code workingDir} is the
     * directory the CLI runs in. When {@code mcp} is non-null and the
     * provider is Claude, the seat is wired to its review MCP server so it
     * can call get_pr_diff / report_finding / … directly.
     */
    public Result run(
            Provider provider, String prompt, String resumeSessionId, Path workingDir, McpEndpoint mcp)
    {
        try {
            slots.acquire();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliReviewException("interrupted waiting for a CLI review slot", e);
        }
        try {
            return runOnce(provider, prompt, resumeSessionId, workingDir, mcp, null);
        }
        finally {
            slots.release();
        }
    }

    /** Run when {@link com.bytequay.app.service.threads.AgentScheduler} has
     * already acquired the shared CLI lane. The ordinary {@link #run} gate
     * remains for legacy reviewer seats that call this component directly. */
    public Result runWithSchedulerCapacity(
            Provider provider, String prompt, String resumeSessionId, Path workingDir, McpEndpoint mcp)
    {
        return runOnce(provider, prompt, resumeSessionId, workingDir, mcp, null);
    }

    public Result runWithSchedulerCapacity(
            Provider provider, String prompt, String resumeSessionId, Path workingDir,
            McpEndpoint mcp, int costCapCents)
    {
        return runOnce(provider, prompt, resumeSessionId, workingDir, mcp, costCapCents);
    }

    private Result runOnce(
            Provider provider, String prompt, String resumeSessionId, Path workingDir,
            McpEndpoint mcp, Integer costCapCents)
    {
        String binary = provider.binary();
        // Codex takes the prompt as a trailing argv arg; Claude reads it
        // from stdin.
        String argvPrompt = provider == Provider.CODEX ? prompt : null;
        // Only Claude's CLI supports our --mcp-config bridge today.
        Path mcpConfig = (provider == Provider.CLAUDE && mcp != null) ? writeMcpConfig(mcp) : null;
        List<String> argv = buildArgv(
                provider, binary, resumeSessionId, workingDir.toString(), argvPrompt,
                mcpConfig, costCapCents);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        }
        catch (IOException e) {
            throw new CliReviewException(
                    "could not start " + binary + " (is it on PATH?): " + e.getMessage(), e);
        }

        deliverPrompt(process, provider, prompt);
        Thread stderrDrain = drainStderr(process, binary);

        CliStreamParser parser = provider == Provider.CLAUDE
                ? new StreamJsonParser(mapper)
                : new CodexJsonParser(mapper);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        catch (IOException e) {
            log.warn("CLI reviewer {} stdout read failed: {}", binary, e.getMessage());
        }

        boolean exited;
        try {
            exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CliReviewException("CLI reviewer " + binary + " interrupted", e);
        }
        if (!exited) {
            process.destroyForcibly();
            throw new CliReviewException("CLI reviewer " + binary + " timed out after "
                    + timeoutMs + "ms");
        }
        stderrDrain.interrupt();

        return withProcessExit(assemble(parser, lines), process.exitValue(), costCapCents);
    }

    /** Whether {@code binary} resolves to an executable on PATH — the
     *  CLI equivalent of "has an API key", used to mark a roster entry
     *  configured. Scans {@code $PATH} without spawning anything. */
    public static boolean isOnPath(String binary)
    {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : Splitter.on(File.pathSeparatorChar).split(path)) {
            if (dir.isBlank()) {
                continue;
            }
            if (Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }

    /** The review tools, with the CLI's {@code mcp__<server>__}
     *  prefix, pre-allowed so {@code claude -p} runs them without a
     *  permission prompt. */
    static final String ALLOWED_REVIEW_TOOLS =
            "mcp__bytequay__get_pr_diff,mcp__bytequay__get_file_content,"
            + "mcp__bytequay__search_code,mcp__bytequay__report_finding,"
            + "mcp__bytequay__record_assignment,mcp__bytequay__record_hypothesis,"
            + "mcp__bytequay__record_step,mcp__bytequay__read_diff,"
            + "mcp__bytequay__read_file,mcp__bytequay__search_diff,"
            + "mcp__bytequay__record_evidence,mcp__bytequay__record_finding,"
            + "mcp__bytequay__record_verification";

    /** The review MCP endpoint URL for a {@code (passId, participantId)}
     *  seat. Pure — unit-tested. */
    static String mcpServerUrl(McpEndpoint mcp)
    {
        if (mcp.explicitUrl() != null && !mcp.explicitUrl().isBlank()) {
            return mcp.explicitUrl();
        }
        return SIDECAR_BASE_URL + "/api/reviews/" + mcp.passId()
                + "/seats/" + mcp.participantId() + "/mcp";
    }

    /** The {@code --mcp-config} JSON the CLI reads. Pure — unit-tested. */
    static String mcpConfigJson(McpEndpoint mcp)
    {
        return "{\"mcpServers\":{\"bytequay\":{\"type\":\"http\",\"url\":\""
                + mcpServerUrl(mcp) + "\"}}}";
    }

    private static Path writeMcpConfig(McpEndpoint mcp)
    {
        try {
            Path file = Files.createTempFile("bytequay-review-mcp-", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, mcpConfigJson(mcp));
            return file;
        }
        catch (IOException e) {
            throw new CliReviewException("could not write review MCP config: " + e.getMessage(), e);
        }
    }

    /** Build the argv for one turn. Pure — unit-tested. {@code mcpConfig}
     *  (Claude only) wires the review MCP server + pre-allows its tools. */
    static List<String> buildArgv(
            Provider provider, String binary, String resumeSessionId, String workingDir,
            String argvPrompt, Path mcpConfig)
    {
        return buildArgv(
                provider, binary, resumeSessionId, workingDir, argvPrompt, mcpConfig, null);
    }

    static List<String> buildArgv(
            Provider provider, String binary, String resumeSessionId, String workingDir,
            String argvPrompt, Path mcpConfig, Integer costCapCents)
    {
        boolean resuming = resumeSessionId != null && !resumeSessionId.isBlank();
        if (provider == Provider.CLAUDE) {
            // The prompt arrives on stdin. With an MCP config the seat gets
            // the real review tools (get_pr_diff / report_finding / …),
            // pre-allowed so -p mode never stops for a permission prompt.
            ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                    .add(binary)
                    .add("-p")
                    .add("--output-format", "stream-json")
                    .add("--verbose");
            if (costCapCents != null) {
                if (costCapCents <= 0) {
                    throw new IllegalArgumentException("CLI review budget must be positive");
                }
                argv.add("--max-budget-usd", BigDecimal.valueOf(costCapCents, 2)
                        .stripTrailingZeros().toPlainString());
            }
            if (mcpConfig != null) {
                argv.add("--mcp-config", mcpConfig.toString());
                argv.add("--allowedTools", ALLOWED_REVIEW_TOOLS);
            }
            if (resuming) {
                argv.add("--resume", resumeSessionId);
            }
            return argv.build();
        }
        // Codex. `exec resume` rejects --sandbox / -C (they're recorded on
        // the session), so the first turn and a resume take different argv:
        //   first:  codex exec --json --skip-git-repo-check --sandbox read-only -C <dir> <prompt>
        //   resume: codex exec resume --json --skip-git-repo-check <id> <prompt>
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(binary)
                .add("exec");
        if (resuming) {
            argv.add("resume")
                    .add("--json")
                    .add("--skip-git-repo-check")
                    .add(resumeSessionId);
        }
        else {
            argv.add("--json")
                    .add("--skip-git-repo-check")
                    .add("--sandbox", "read-only")
                    .add("-C", workingDir);
        }
        if (argvPrompt != null) {
            argv.add(argvPrompt);
        }
        return argv.build();
    }

    /** Fold the parsed stdout into one result: last session id wins, the
     *  assistant texts join, the turn costs sum. Pure — unit-tested. */
    static Result assemble(CliStreamParser parser, List<String> stdoutLines)
    {
        StringBuilder text = new StringBuilder();
        String sessionId = null;
        long costUsdMilli = 0;
        String errorMessage = null;
        int exitCode = 0;
        Instant now = Instant.EPOCH;
        for (String line : stdoutLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            for (StreamEvent event : parser.parse(line, now)) {
                if (event instanceof StreamEvent.SessionStarted started) {
                    sessionId = started.sessionId();
                }
                else if (event instanceof StreamEvent.AssistantText assistant
                        && assistant.text() != null && !assistant.text().isBlank()) {
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    text.append(assistant.text().strip());
                }
                else if (event instanceof StreamEvent.TurnDone done) {
                    costUsdMilli += done.costUsdMilli();
                }
                else if (event instanceof StreamEvent.ErrorOccurred error) {
                    errorMessage = error.message();
                }
                else if (event instanceof StreamEvent.SessionEnded ended) {
                    exitCode = ended.exitCode();
                    if (ended.errorMessage() != null && !ended.errorMessage().isBlank()) {
                        errorMessage = ended.errorMessage();
                    }
                }
            }
        }
        String end = errorMessage == null && exitCode == 0 ? "COMPLETED"
                : isBudgetFailure(errorMessage) ? "ABORTED" : "ERRORED";
        return new Result(
                text.toString().strip(), sessionId, costUsdMilli, end, errorMessage);
    }

    static Result withProcessExit(Result result, int exitCode, Integer costCapCents)
    {
        if (exitCode == 0 || !"COMPLETED".equals(result.end())) {
            return result;
        }
        boolean capReached = costCapCents != null
                && result.costUsdMilli() >= (long) costCapCents * 10;
        return new Result(
                result.text(), result.sessionId(), result.costUsdMilli(),
                capReached ? "ABORTED" : "ERRORED",
                capReached ? "CLI review reached its budget cap"
                        : "CLI reviewer exited with code " + exitCode);
    }

    private static boolean isBudgetFailure(String message)
    {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("budget") || lower.contains("cost limit")
                || lower.contains("spending limit");
    }

    private static void deliverPrompt(Process process, Provider provider, String prompt)
    {
        try (OutputStream stdin = process.getOutputStream()) {
            if (provider == Provider.CLAUDE) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            // Codex carries the prompt in argv; closing stdin lets it proceed.
        }
        catch (IOException e) {
            log.warn("CLI reviewer prompt delivery failed: {}", e.getMessage());
        }
    }

    /** Drain stderr on a daemon thread so a chatty CLI can't deadlock by
     *  filling its stderr pipe while we read stdout. */
    private static Thread drainStderr(Process process, String binary)
    {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[{} stderr] {}", binary, line);
                }
            }
            catch (IOException ignored) {
                // Pipe closed when the process exits — nothing to do.
            }
        }, "cli-review-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
