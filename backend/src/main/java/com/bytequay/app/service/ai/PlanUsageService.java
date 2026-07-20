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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/** Reads provider-reported subscription limits from local CLI state. */
@Service
public class PlanUsageService
{
    private static final int MAX_CODEX_FILES = 12;
    private static final int MAX_TAIL_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;
    private final Path codexSessions;
    private final Path claudeConfig;
    private final Path claudeStatusLineCache;

    @Autowired
    public PlanUsageService(ObjectMapper mapper)
    {
        this(
                mapper,
                Path.of(System.getProperty("user.home"), ".codex", "sessions"),
                Path.of(System.getProperty("user.home"), ".claude"),
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "ByteQuay", "claude-statusline.json"));
    }

    PlanUsageService(ObjectMapper mapper, Path codexSessions, Path claudeConfig, Path claudeStatusLineCache)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.codexSessions = requireNonNull(codexSessions, "codexSessions is null");
        this.claudeConfig = requireNonNull(claudeConfig, "claudeConfig is null");
        this.claudeStatusLineCache = requireNonNull(claudeStatusLineCache, "claudeStatusLineCache is null");
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
        List<ProviderUsage> providers = new ArrayList<>();
        readCodex().ifPresent(providers::add);
        readClaude().ifPresent(providers::add);
        return new PlanUsage(List.copyOf(providers));
    }

    private Optional<ProviderUsage> readCodex()
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
        return Optional.of(unavailable(
                "openai", "Codex", "Codex has not reported a plan limit yet."));
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
                        "Codex",
                        plan,
                        timestamp(root.path("timestamp"), lastModified(path)),
                        "Codex local session",
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
        if (!Files.isDirectory(claudeConfig) && !Files.isRegularFile(claudeStatusLineCache)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(claudeStatusLineCache)) {
            return Optional.of(unavailable(
                    "anthropic", "Claude", "Enable Claude usage sync to show plan limits."));
        }
        try {
            JsonNode root = mapper.readTree(claudeStatusLineCache.toFile());
            JsonNode rateLimits = root.path("rate_limits");
            List<LimitWindow> limits = new ArrayList<>();
            addClaudeWindow(limits, "five_hour", "5-hour", rateLimits.path("five_hour"));
            addClaudeWindow(limits, "seven_day", "Weekly", rateLimits.path("seven_day"));
            if (limits.isEmpty()) {
                return Optional.of(unavailable(
                        "anthropic", "Claude", "Waiting for Claude Code to report plan limits."));
            }
            return Optional.of(new ProviderUsage(
                    "anthropic",
                    "Claude",
                    null,
                    lastModified(claudeStatusLineCache),
                    "Claude Code status line",
                    null,
                    List.copyOf(limits)));
        }
        catch (IOException | RuntimeException ignored) {
            return Optional.of(unavailable(
                    "anthropic", "Claude", "Claude usage sync has not produced a valid snapshot."));
        }
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
}
