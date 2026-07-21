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
package com.bytequay.app.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/** Reads provider-reported subscription limits from local CLI state. */
@Service
public class PlanUsageService
{
    private static final int MAX_CODEX_FILES = 12;
    private static final int MAX_TAIL_BYTES = 1024 * 1024;
    private static final Duration CODEX_PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CLAUDE_PROBE_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration CLAUDE_CACHE_MAX_AGE = Duration.ofMinutes(15);
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|[()][A-Z0-9]|[78])");
    // No line-start anchor: the screen-reader TUI's cursor-move/erase escapes get
    // stripped to nothing, which glues a heading onto the preceding chrome
    // ("Esc to cancelCurrent session"), so a heading is not reliably at a line
    // start. The heading strings are distinctive enough to match mid-line.
    private static final Pattern CLAUDE_LIMIT = Pattern.compile(
            "(Current session|Current week \\(([^)]+)\\))\\s*\\R+"
                    + "(?:\\d+(?:\\.\\d+)?%\\s+)?(\\d+(?:\\.\\d+)?)% used\\s*\\R+"
                    + "Resets ([^\\r\\n]+)");
    private static final Pattern CLAUDE_PLAN = Pattern.compile(
            "Claude (Max(?: \\d+x)?|Pro|Team|Enterprise)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESET_ZONE = Pattern.compile("^(.*) \\(([^()]+)\\)$");
    private static final DateTimeFormatter CLAUDE_RESET_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("uuuu MMM d 'at' h")
            .optionalStart()
            .appendPattern(":mm")
            .optionalEnd()
            .appendPattern("a")
            .toFormatter(Locale.US);
    private static final DateTimeFormatter CLAUDE_RESET_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("h")
            .optionalStart()
            .appendPattern(":mm")
            .optionalEnd()
            .appendPattern("a")
            .toFormatter(Locale.US);
    private static final String CLAUDE_EXPECT_SCRIPT = """
            set timeout 25
            log_user 1
            spawn -noecho $env(BYTEQUAY_CLAUDE_BINARY) --ax-screen-reader --safe-mode
            expect {
              -re {\\$\\x1b\\[4G} {}
              timeout { exit 2 }
              eof { exit 2 }
            }
            send -- "/usage\\r"
            expect {
              -re {Usage credits} {}
              timeout {}
              eof {}
            }
            send -- "\\033"
            after 250
            send -- "/exit\\r"
            set timeout 5
            expect {
              eof {}
              timeout {
                send -- "\\003"
                expect eof
              }
            }
            """;

    private final ObjectMapper mapper;
    private final Path codexSessions;
    private final Path claudeConfig;
    private final Path claudeStatusLineCache;
    private final Path claudeProbeCache;
    private final String codexBinary;
    private final String claudeBinary;

    @Autowired
    public PlanUsageService(ObjectMapper mapper)
    {
        this(
                mapper,
                Path.of(System.getProperty("user.home"), ".codex", "sessions"),
                Path.of(System.getProperty("user.home"), ".claude"),
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "ByteQuay", "claude-statusline.json"),
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "ByteQuay", "claude-plan-usage.json"),
                findCodexBinary().orElse(null),
                findClaudeBinary().orElse(null));
    }

    PlanUsageService(ObjectMapper mapper, Path codexSessions, Path claudeConfig, Path claudeStatusLineCache)
    {
        this(mapper, codexSessions, claudeConfig, claudeStatusLineCache,
                claudeStatusLineCache.resolveSibling("claude-plan-usage.json"),
                codexSessions.resolve("codex-cli-test").toString(),
                claudeConfig.resolve("claude-cli-test").toString());
    }

    PlanUsageService(
            ObjectMapper mapper,
            Path codexSessions,
            Path claudeConfig,
            Path claudeStatusLineCache,
            Path claudeProbeCache)
    {
        this(mapper, codexSessions, claudeConfig, claudeStatusLineCache, claudeProbeCache, "codex", "claude");
    }

    PlanUsageService(
            ObjectMapper mapper,
            Path codexSessions,
            Path claudeConfig,
            Path claudeStatusLineCache,
            Path claudeProbeCache,
            String codexBinary,
            String claudeBinary)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.codexSessions = requireNonNull(codexSessions, "codexSessions is null");
        this.claudeConfig = requireNonNull(claudeConfig, "claudeConfig is null");
        this.claudeStatusLineCache = requireNonNull(claudeStatusLineCache, "claudeStatusLineCache is null");
        this.claudeProbeCache = requireNonNull(claudeProbeCache, "claudeProbeCache is null");
        this.codexBinary = codexBinary;
        this.claudeBinary = claudeBinary;
    }

    public record PlanUsage(List<ProviderUsage> providers) {}

    public record ProviderUsage(
            String provider,
            String label,
            String plan,
            long updatedAt,
            String source,
            String message,
            List<LimitWindow> limits) {}

    public record LimitWindow(
            String id,
            String label,
            double usedPercent,
            long resetsAt,
            String model) {}

    public PlanUsage current()
    {
        return new PlanUsage(List.of(codexUsage(), claudeUsage()));
    }

    /** Run Claude's local interactive /usage command and cache its normalized result. */
    public synchronized PlanUsage refreshClaude()
    {
        if (claudeBinary == null) {
            throw new IllegalStateException("Claude CLI is not available");
        }
        Instant capturedAt = Instant.now();
        String output = runClaudeUsage();
        ProviderUsage usage = parseClaudeUsage(output, capturedAt);
        if (usage.limits().isEmpty()) {
            throw new IllegalStateException("Claude CLI did not return plan usage");
        }
        writeClaudeProbe(usage);
        return current();
    }

    private ProviderUsage codexUsage()
    {
        if (codexBinary == null) {
            return unavailable("openai", "Codex CLI", "Codex CLI is not available.");
        }
        try {
            return readCodexAppServer();
        }
        catch (RuntimeException ignored) {
            return readCodexSession().orElseGet(() -> unavailable(
                    "openai", "Codex CLI", "Codex CLI could not report plan limits."));
        }
    }

    private ProviderUsage claudeUsage()
    {
        if (claudeBinary == null) {
            return unavailable("anthropic", "Claude CLI", "Claude CLI is not available.");
        }
        return readClaude().orElseGet(() -> unavailable(
                "anthropic", "Claude CLI", "Refresh Claude CLI usage to read plan limits."));
    }

    private ProviderUsage readCodexAppServer()
    {
        Process process = null;
        try {
            process = new ProcessBuilder(codexBinary, "app-server")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write("""
                        {"method":"initialize","id":0,"params":{"clientInfo":{"name":"bytequay","title":"ByteQuay","version":"0.2.0"}}}
                        {"method":"initialized","params":{}}
                        {"method":"account/rateLimits/read","id":1}
                        """);
                writer.flush();
                long deadline = System.nanoTime() + CODEX_PROBE_TIMEOUT.toNanos();
                while (System.nanoTime() < deadline) {
                    if (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        JsonNode response = mapper.readTree(line);
                        if (response.path("id").asInt(-1) == 1) {
                            return parseCodexUsage(response, Instant.now());
                        }
                    }
                    else if (!process.isAlive()) {
                        break;
                    }
                    else {
                        Thread.sleep(20);
                    }
                }
            }
            throw new IllegalStateException("Codex CLI usage request timed out");
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not read Codex CLI usage", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex CLI usage request was interrupted", e);
        }
        finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    static ProviderUsage parseCodexUsage(JsonNode response, Instant capturedAt)
    {
        JsonNode result = response.path("result");
        JsonNode main = result.path("rateLimits");
        if (!main.isObject()) {
            throw new IllegalStateException("Codex CLI returned no rate limits");
        }
        List<LimitWindow> limits = new ArrayList<>();
        addCodexBucket(limits, main);
        String mainId = main.path("limitId").asText();
        JsonNode buckets = result.path("rateLimitsByLimitId");
        if (buckets.isObject()) {
            buckets.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals(mainId)) {
                    addCodexBucket(limits, entry.getValue());
                }
            });
        }
        String plan = textOrNull(main.path("planType"));
        return new ProviderUsage(
                "openai",
                "Codex CLI",
                plan,
                capturedAt.toEpochMilli(),
                "Codex CLI app-server",
                null,
                List.copyOf(limits));
    }

    private static void addCodexBucket(List<LimitWindow> limits, JsonNode bucket)
    {
        String bucketId = bucket.path("limitId").asText("codex");
        String model = displayTextOrNull(bucket.path("limitName"));
        addCodexAppServerWindow(limits, bucketId, "primary", bucket.path("primary"), model);
        addCodexAppServerWindow(limits, bucketId, "secondary", bucket.path("secondary"), model);
    }

    private static void addCodexAppServerWindow(
            List<LimitWindow> limits,
            String bucketId,
            String slot,
            JsonNode window,
            String model)
    {
        if (!window.isObject() || !window.path("usedPercent").isNumber()) {
            return;
        }
        int minutes = window.path("windowDurationMins").asInt(0);
        limits.add(new LimitWindow(
                bucketId + ":" + slot + ":" + minutes,
                windowLabel(minutes),
                percent(window.path("usedPercent").asDouble()),
                secondsToMillis(window.path("resetsAt").asLong(0)),
                model));
    }

    private Optional<ProviderUsage> readCodexSession()
    {
        if (!Files.isDirectory(codexSessions)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.find(
                codexSessions,
                4,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().startsWith("rollout-")
                        && path.getFileName().toString().endsWith(".jsonl"))) {
            List<Path> candidates = paths
                    .sorted(Comparator.comparingLong(PlanUsageService::lastModified).reversed())
                    .limit(MAX_CODEX_FILES)
                    .toList();
            for (Path candidate : candidates) {
                Optional<ProviderUsage> usage = readCodexFile(candidate);
                if (usage.isPresent()) {
                    return usage;
                }
            }
        }
        catch (IOException | RuntimeException ignored) {
            // Provider usage is optional UI data; a malformed local file must not fail the page.
        }
        return Optional.empty();
    }

    private Optional<ProviderUsage> readCodexFile(Path path)
    {
        try {
            String[] lines = readTail(path).split("\\R");
            for (int index = lines.length - 1; index >= 0; index--) {
                String line = lines[index];
                if (!line.contains("\"rate_limits\"") || !line.contains("\"token_count\"")) {
                    continue;
                }
                JsonNode root = mapper.readTree(line);
                JsonNode rateLimits = root.path("payload").path("rate_limits");
                if (!rateLimits.isObject()) {
                    continue;
                }
                List<LimitWindow> limits = new ArrayList<>();
                addCodexWindow(limits, "primary", rateLimits.path("primary"));
                addCodexWindow(limits, "secondary", rateLimits.path("secondary"));
                limits.sort(Comparator.comparingInt(window -> windowMinutes(window.id())));
                if (limits.isEmpty()) {
                    continue;
                }
                String plan = textOrNull(rateLimits.path("plan_type"));
                return Optional.of(new ProviderUsage(
                        "openai",
                        "Codex CLI",
                        plan,
                        timestamp(root.path("timestamp"), lastModified(path)),
                        "Codex CLI local session",
                        null,
                        List.copyOf(limits)));
            }
        }
        catch (IOException | RuntimeException ignored) {
            // Try the next recent session file.
        }
        return Optional.empty();
    }

    private void addCodexWindow(List<LimitWindow> limits, String slot, JsonNode window)
    {
        if (!window.isObject() || !window.path("used_percent").isNumber()) {
            return;
        }
        int minutes = window.path("window_minutes").asInt(0);
        limits.add(new LimitWindow(
                slot + ":" + minutes,
                windowLabel(minutes),
                percent(window.path("used_percent").asDouble()),
                secondsToMillis(window.path("resets_at").asLong(0)),
                null));
    }

    private Optional<ProviderUsage> readClaude()
    {
        if (!Files.isDirectory(claudeConfig)
                && !Files.isRegularFile(claudeStatusLineCache)
                && !Files.isRegularFile(claudeProbeCache)) {
            return Optional.empty();
        }
        Optional<ProviderUsage> probe = readClaudeProbe();
        if (probe.isPresent()) {
            return probe;
        }
        if (!Files.isRegularFile(claudeStatusLineCache)) {
            return Optional.of(unavailable(
                    "anthropic", "Claude CLI", "Refresh Claude CLI usage to read plan limits."));
        }
        try {
            JsonNode root = mapper.readTree(claudeStatusLineCache.toFile());
            JsonNode rateLimits = root.path("rate_limits");
            List<LimitWindow> limits = new ArrayList<>();
            addClaudeWindow(limits, "five_hour", "5-hour", rateLimits.path("five_hour"));
            addClaudeWindow(limits, "seven_day", "Weekly", rateLimits.path("seven_day"));
            if (limits.isEmpty()) {
                return Optional.of(unavailable(
                        "anthropic", "Claude CLI", "Waiting for Claude CLI to report plan limits."));
            }
            return Optional.of(new ProviderUsage(
                    "anthropic",
                    "Claude CLI",
                    null,
                    lastModified(claudeStatusLineCache),
                    "Claude CLI status line",
                    null,
                    List.copyOf(limits)));
        }
        catch (IOException | RuntimeException ignored) {
            return Optional.of(unavailable(
                    "anthropic", "Claude CLI", "Claude CLI usage sync has not produced a valid snapshot."));
        }
    }

    private Optional<ProviderUsage> readClaudeProbe()
    {
        if (!Files.isRegularFile(claudeProbeCache)
                || lastModified(claudeProbeCache) < Instant.now().minus(CLAUDE_CACHE_MAX_AGE).toEpochMilli()) {
            return Optional.empty();
        }
        try {
            ProviderUsage usage = mapper.readValue(claudeProbeCache.toFile(), ProviderUsage.class);
            if (!"anthropic".equals(usage.provider()) || usage.limits() == null || usage.limits().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(usage);
        }
        catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String runClaudeUsage()
    {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/expect", "-c", CLAUDE_EXPECT_SCRIPT)
                    .directory(Path.of(System.getProperty("user.home")).toFile())
                    .redirectErrorStream(true);
            builder.environment().put("BYTEQUAY_CLAUDE_BINARY", claudeBinary);
            process = builder.start();
            if (!process.waitFor(CLAUDE_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Claude CLI usage refresh timed out");
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not start Claude CLI usage refresh", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("Claude CLI usage refresh was interrupted", e);
        }
    }

    static ProviderUsage parseClaudeUsage(String output, Instant capturedAt)
    {
        String clean = ANSI_ESCAPE.matcher(requireNonNull(output, "output is null")).replaceAll("")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        Map<String, LimitWindow> limits = new LinkedHashMap<>();
        Matcher matcher = CLAUDE_LIMIT.matcher(clean);
        while (matcher.find()) {
            String heading = matcher.group(1);
            String scope = matcher.group(2);
            double used = percent(Double.parseDouble(matcher.group(3)));
            long resetsAt = parseClaudeReset(matcher.group(4).trim(), capturedAt);
            if ("Current session".equals(heading)) {
                limits.put("current_session", new LimitWindow(
                        "current_session", "Current session", used, resetsAt, null));
            }
            else if (scope != null && "all models".equalsIgnoreCase(scope)) {
                limits.put("all_models", new LimitWindow(
                        "all_models", "All models", used, resetsAt, null));
            }
            else if (scope != null) {
                String model = titleCase(scope);
                limits.put("model:" + scope.toLowerCase(Locale.ROOT), new LimitWindow(
                        "model:" + scope.toLowerCase(Locale.ROOT), "Weekly", used, resetsAt, model));
            }
        }
        Matcher planMatcher = CLAUDE_PLAN.matcher(clean);
        String plan = planMatcher.find() ? titleCase(planMatcher.group(1)) : null;
        return new ProviderUsage(
                "anthropic",
                "Claude CLI",
                plan,
                capturedAt.toEpochMilli(),
                "Claude CLI /usage",
                null,
                List.copyOf(limits.values()));
    }

    private void writeClaudeProbe(ProviderUsage usage)
    {
        Path temporary = null;
        try {
            Files.createDirectories(claudeProbeCache.getParent());
            temporary = Files.createTempFile(claudeProbeCache.getParent(), ".claude-plan-usage-", ".json");
            mapper.writeValue(temporary.toFile(), usage);
            try {
                Files.move(temporary, claudeProbeCache,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, claudeProbeCache, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not cache Claude plan usage", e);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // Best effort cleanup after a failed cache move.
                }
            }
        }
    }

    private static long parseClaudeReset(String value, Instant capturedAt)
    {
        Matcher zoneMatcher = RESET_ZONE.matcher(value);
        String text = zoneMatcher.matches() ? zoneMatcher.group(1).trim() : value;
        ZoneId zone;
        try {
            zone = zoneMatcher.matches() ? ZoneId.of(zoneMatcher.group(2)) : ZoneId.systemDefault();
        }
        catch (RuntimeException ignored) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = capturedAt.atZone(zone);
        try {
            ZonedDateTime reset;
            if (text.contains(" at ")) {
                LocalDateTime dateTime = LocalDateTime.parse(
                        now.getYear() + " " + text, CLAUDE_RESET_DATE);
                reset = dateTime.atZone(zone);
                if (reset.isBefore(now)) {
                    reset = reset.plusYears(1);
                }
            }
            else {
                LocalTime time = LocalTime.parse(text, CLAUDE_RESET_TIME);
                reset = now.toLocalDate().atTime(time).atZone(zone);
                if (reset.isBefore(now)) {
                    reset = reset.plusDays(1);
                }
            }
            return reset.toInstant().toEpochMilli();
        }
        catch (DateTimeParseException ignored) {
            return 0;
        }
    }

    private static Optional<String> findCodexBinary()
    {
        return findExecutable("codex", List.of(
                Path.of(System.getProperty("user.home"), ".local", "bin", "codex"),
                Path.of("/opt/homebrew/bin/codex"),
                Path.of("/usr/local/bin/codex")));
    }

    private static Optional<String> findClaudeBinary()
    {
        return findExecutable("claude", List.of(
                Path.of(System.getProperty("user.home"), ".local", "bin", "claude"),
                Path.of("/opt/homebrew/bin/claude"),
                Path.of("/usr/local/bin/claude")));
    }

    private static Optional<String> findExecutable(String name, List<Path> preferred)
    {
        String path = System.getenv("PATH");
        Stream<Path> fromPath = path == null ? Stream.empty() : Stream.of(path.split(":"))
                .filter(directory -> !directory.isBlank())
                .map(directory -> Path.of(directory).resolve(name));
        return Stream.concat(preferred.stream(), fromPath)
                .filter(Files::isExecutable)
                .findFirst()
                .map(Path::toString);
    }

    private static String titleCase(String value)
    {
        return Stream.of(value.trim().split("\\s+"))
                .filter(word -> !word.isBlank())
                .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT)
                        + word.substring(1).toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + " " + right)
                .orElse(value);
    }

    private static void addClaudeWindow(List<LimitWindow> limits, String id, String label, JsonNode window)
    {
        if (!window.isObject() || !window.path("used_percentage").isNumber()) {
            return;
        }
        limits.add(new LimitWindow(
                id,
                label,
                percent(window.path("used_percentage").asDouble()),
                secondsToMillis(window.path("resets_at").asLong(0)),
                null));
    }

    private static ProviderUsage unavailable(String provider, String label, String message)
    {
        return new ProviderUsage(provider, label, null, 0, null, message, List.of());
    }

    private static String readTail(Path path)
            throws IOException
    {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            int bytes = (int) Math.min(length, MAX_TAIL_BYTES);
            byte[] tail = new byte[bytes];
            file.seek(length - bytes);
            file.readFully(tail);
            String value = new String(tail, StandardCharsets.UTF_8);
            int firstNewline = value.indexOf('\n');
            return length > bytes && firstNewline >= 0 ? value.substring(firstNewline + 1) : value;
        }
    }

    private static long timestamp(JsonNode value, long fallback)
    {
        if (!value.isTextual()) {
            return fallback;
        }
        try {
            return Instant.parse(value.asText()).toEpochMilli();
        }
        catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static long lastModified(Path path)
    {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        }
        catch (IOException ignored) {
            return 0;
        }
    }

    private static String windowLabel(int minutes)
    {
        if (minutes == 300) {
            return "5-hour";
        }
        if (minutes == 10_080) {
            return "Weekly";
        }
        if (minutes > 0 && minutes % 1440 == 0) {
            return (minutes / 1440) + "-day";
        }
        if (minutes > 0 && minutes % 60 == 0) {
            return (minutes / 60) + "-hour";
        }
        return "Plan limit";
    }

    private static int windowMinutes(String id)
    {
        int separator = id.lastIndexOf(':');
        if (separator < 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(id.substring(separator + 1));
        }
        catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static double percent(double value)
    {
        return Math.max(0, Math.min(100, value));
    }

    private static long secondsToMillis(long seconds)
    {
        return seconds <= 0 ? 0 : seconds * 1000;
    }

    private static String textOrNull(JsonNode value)
    {
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().toLowerCase(Locale.ROOT);
    }

    private static String displayTextOrNull(JsonNode value)
    {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
