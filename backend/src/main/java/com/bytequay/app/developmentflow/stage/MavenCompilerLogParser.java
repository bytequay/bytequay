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
package com.bytequay.app.developmentflow.stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Deterministic parser for Maven compiler diagnostics from a complete job log. */
public final class MavenCompilerLogParser
{
    public static final String SOURCE = "ACTIONS_JOB_LOG_V1";
    public static final String PARSER = "MAVEN_COMPILER_V1";
    public static final int VERSION = 1;

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*m");
    private static final Pattern MAVEN_LINE = Pattern.compile(
            "\\[(ERROR|INFO|WARNING)]\\s*(.*)$");
    private static final Pattern ACTIONS_COMPILER_CONTINUATION = Pattern.compile(
            "^\\uFEFF?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
                    + "(?:\\.\\d+)?Z\\s{2,}"
                    + "((?:symbol|location|required|found|reason):\\s*.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^(.*?\\.java):?\\[(\\d+)(?:,(\\d+))?]\\s*(.*)$");
    private static final Pattern RUNNER_PATH = Pattern.compile(
            "^(?:/home/runner/work|/__w)/[^/]+/[^/]+/(.+)$");
    private static final Pattern WINDOWS_RUNNER_PATH = Pattern.compile(
            "^[A-Za-z]:/(?:a|_work)/[^/]+/[^/]+/(.+)$");
    private static final Pattern LINE_COLUMN = Pattern.compile(
            "(?i)(?::)?\\[\\d+(?:,\\d+)?]|\\bline\\s+\\d+"
                    + "(?:\\s*,\\s*column\\s+\\d+)?\\b");
    private static final Comparator<Diagnostic> DIAGNOSTIC_ORDER =
            Comparator.comparing(Diagnostic::file)
                    .thenComparing(Diagnostic::kind)
                    .thenComparing(Diagnostic::message)
                    .thenComparing(Diagnostic::symbol,
                            Comparator.nullsFirst(String::compareTo))
                    .thenComparing(Diagnostic::location,
                            Comparator.nullsFirst(String::compareTo));

    private MavenCompilerLogParser() {}

    public static Proof parse(String rawLog)
    {
        requireNonNull(rawLog, "rawLog is null");
        List<Diagnostic> diagnostics = new ArrayList<>();
        Builder current = null;
        boolean sawSection = false;
        boolean inSection = false;
        boolean sectionHasDiagnostic = false;
        boolean incomplete = false;

        for (String rawLine : rawLog.split("\\R", -1)) {
            LevelLine line = levelLine(rawLine);
            if (line != null && line.level().equals("ERROR")
                    && isCompilationHeader(line.body())) {
                if (current != null) {
                    diagnostics.add(current.build());
                    current = null;
                }
                sawSection = true;
                inSection = true;
                sectionHasDiagnostic = false;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line == null) {
                String continuation = actionsCompilerContinuation(rawLine);
                if (current != null && continuation != null
                        && !current.append(continuation)) {
                    incomplete = true;
                }
                continue;
            }
            if (!line.level().equals("ERROR")) {
                if (current != null) {
                    diagnostics.add(current.build());
                    current = null;
                    sectionHasDiagnostic = true;
                }
                if (!isSeparatorOrCount(line.body())) {
                    inSection = false;
                }
                else if (sectionHasDiagnostic) {
                    inSection = false;
                }
                continue;
            }

            String body = normalizedText(line.body());
            if (body.isEmpty() || isSeparatorOrCount(body)) {
                continue;
            }
            if (isCompilerBoilerplate(body)) {
                if (current != null) {
                    diagnostics.add(current.build());
                    current = null;
                    sectionHasDiagnostic = true;
                }
                inSection = false;
                continue;
            }
            Matcher diagnostic = DIAGNOSTIC.matcher(body);
            if (diagnostic.matches()) {
                if (current != null) {
                    diagnostics.add(current.build());
                    sectionHasDiagnostic = true;
                }
                String file = canonicalFile(diagnostic.group(1));
                String message = canonicalMessage(diagnostic.group(4));
                if (file == null || message.isEmpty()) {
                    incomplete = true;
                    current = null;
                }
                else {
                    current = new Builder(file, message);
                }
                continue;
            }
            if (current == null) {
                incomplete = true;
                continue;
            }
            if (!current.append(body)) {
                incomplete = true;
            }
        }
        if (current != null) {
            diagnostics.add(current.build());
        }

        List<Diagnostic> canonical = diagnostics.stream()
                .sorted(DIAGNOSTIC_ORDER)
                .toList();
        boolean complete = sawSection && !canonical.isEmpty() && !incomplete;
        List<String> fingerprints = complete
                ? canonical.stream()
                        .map(MavenCompilerLogParser::fingerprint)
                        .distinct()
                        .sorted()
                        .toList()
                : List.of();
        return new Proof(
                SOURCE, PARSER, VERSION, complete, canonical, fingerprints);
    }

    private static LevelLine levelLine(String rawLine)
    {
        String plain = ANSI.matcher(rawLine).replaceAll("");
        Matcher matcher = MAVEN_LINE.matcher(plain);
        return matcher.find()
                ? new LevelLine(matcher.group(1), matcher.group(2))
                : null;
    }

    private static String actionsCompilerContinuation(String rawLine)
    {
        String plain = ANSI.matcher(rawLine).replaceAll("");
        Matcher matcher = ACTIONS_COMPILER_CONTINUATION.matcher(plain);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean isCompilationHeader(String body)
    {
        return canonicalText(body).toUpperCase(Locale.ENGLISH)
                .matches("COMPILATION ERROR\\s*:?");
    }

    private static boolean isSeparatorOrCount(String body)
    {
        String value = canonicalText(body);
        return value.matches("[-=]{3,}")
                || value.matches("(?i)\\d+\\s+errors?");
    }

    private static boolean isCompilerBoilerplate(String body)
    {
        String value = body.toLowerCase(Locale.ENGLISH);
        return value.startsWith("failed to execute goal ")
                || value.startsWith("-> [help ")
                || value.startsWith("[help ")
                || value.startsWith("re-run maven ")
                || value.startsWith("to see the full stack trace")
                || value.startsWith("for more information")
                || value.startsWith("after correcting the problems")
                || value.startsWith("you can then resume");
    }

    private static String canonicalFile(String value)
    {
        String path = canonicalText(value).replace('\\', '/');
        Matcher runner = RUNNER_PATH.matcher(path);
        Matcher windowsRunner = WINDOWS_RUNNER_PATH.matcher(path);
        if (runner.matches()) {
            path = runner.group(1);
        }
        else if (windowsRunner.matches()) {
            path = windowsRunner.group(1);
        }
        else if (path.startsWith("/")
                || path.matches("^[A-Za-z]:/.*")) {
            int source = path.indexOf("/src/");
            if (source < 0) {
                return null;
            }
            int module = path.lastIndexOf('/', source - 1);
            path = path.substring(module + 1);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        path = path.replaceAll("/{2,}", "/");
        if (path.isBlank() || path.startsWith("/")
                || path.equals("..") || path.startsWith("../")
                || path.contains("/../") || !path.endsWith(".java")) {
            return null;
        }
        return path;
    }

    private static String canonicalMessage(String value)
    {
        return canonicalText(value).replaceFirst("(?i)^error:\\s*", "");
    }

    private static String canonicalText(String value)
    {
        return LINE_COLUMN.matcher(normalizedText(value)).replaceAll("")
                .trim().replaceAll("\\s+", " ");
    }

    private static String normalizedText(String value)
    {
        if (value == null) {
            return "";
        }
        String plain = ANSI.matcher(value).replaceAll("");
        return plain.trim().replaceAll("\\s+", " ");
    }

    private static String kind(String message)
    {
        String value = message.toLowerCase(Locale.ENGLISH);
        if (value.startsWith("cannot find symbol")) {
            return "CANNOT_FIND_SYMBOL";
        }
        if (value.startsWith("package ") && value.endsWith(" does not exist")) {
            return "PACKAGE_NOT_FOUND";
        }
        if (value.startsWith("incompatible types")) {
            return "INCOMPATIBLE_TYPES";
        }
        if (value.contains("cannot be applied to given types")) {
            return "METHOD_NOT_APPLICABLE";
        }
        if (value.startsWith("reference to ") && value.endsWith(" is ambiguous")) {
            return "AMBIGUOUS_REFERENCE";
        }
        if (value.contains(" has private access in ")) {
            return "ACCESS_DENIED";
        }
        if (value.startsWith("duplicate class:")) {
            return "DUPLICATE_CLASS";
        }
        if (value.startsWith("cannot access ")) {
            return "CANNOT_ACCESS";
        }
        return "COMPILER_ERROR";
    }

    private static String fingerprint(Diagnostic diagnostic)
    {
        return RemoteCiProvenance.canonicalFingerprint(
                new RemoteCiProvenance.CanonicalDiagnostic(
                        diagnostic.file(), "COMPILATION_ERROR",
                        diagnostic.kind(), diagnostic.message(),
                        diagnostic.symbol(), diagnostic.location()));
    }

    public record Diagnostic(
            String file,
            String kind,
            String message,
            String symbol,
            String location)
    {
        public Diagnostic
        {
            requireNonNull(file, "file is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(message, "message is null");
        }
    }

    public record Proof(
            String source,
            String parser,
            int version,
            boolean complete,
            List<Diagnostic> canonicalDiagnostics,
            List<String> fingerprints)
    {
        public Proof
        {
            requireNonNull(source, "source is null");
            requireNonNull(parser, "parser is null");
            canonicalDiagnostics = List.copyOf(requireNonNull(
                    canonicalDiagnostics, "canonicalDiagnostics is null"));
            fingerprints = List.copyOf(requireNonNull(
                    fingerprints, "fingerprints is null"));
            if (!complete && !fingerprints.isEmpty()) {
                throw new IllegalArgumentException(
                        "incomplete parse cannot expose fingerprints");
            }
        }
    }

    private record LevelLine(String level, String body) {}

    private static final class Builder
    {
        private final String file;
        private final String initialMessage;
        private final List<String> continuations = new ArrayList<>();
        private String symbol;
        private String location;

        private Builder(String file, String initialMessage)
        {
            this.file = file;
            this.initialMessage = initialMessage;
        }

        private boolean append(String raw)
        {
            String value = canonicalText(raw);
            String lower = value.toLowerCase(Locale.ENGLISH);
            if (lower.startsWith("symbol:")) {
                if (symbol != null) {
                    return false;
                }
                symbol = canonicalText(value.substring("symbol:".length()));
                return !symbol.isEmpty();
            }
            if (lower.startsWith("location:")) {
                if (location != null) {
                    return false;
                }
                location = canonicalText(value.substring("location:".length()));
                return !location.isEmpty();
            }
            if (value.isEmpty()) {
                return false;
            }
            continuations.add(value);
            return true;
        }

        private Diagnostic build()
        {
            String message = continuations.isEmpty()
                    ? initialMessage
                    : initialMessage + " | " + String.join(" | ", continuations);
            return new Diagnostic(
                    file, kind(initialMessage), message, symbol, location);
        }
    }
}
