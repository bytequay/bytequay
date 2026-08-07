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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure raw-log to stable failure fingerprint parser. */
@Component
public class HarnessLogParser
{
    /** One or more hex segments ending at a word boundary, so a multi-segment
     * generated name — {@code tmp_trino_05cae137_7ee766c9} — is scrubbed whole.
     * Matching a single segment left the leading one behind, which is per-run and
     * broke the fingerprint just as thoroughly as the whole name would. The
     * trailing boundary is what keeps {@code foo_abcdef_bar} intact. */
    private static final Pattern RANDOM_SUFFIX = Pattern.compile(
            "(?:_[0-9a-f]{6,})+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMP_PATH = Pattern.compile("(?:/tmp|/private/tmp|[A-Za-z]:\\\\Temp)[/\\\\]\\S+");
    private static final Pattern TIMESTAMP = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}T[\\d:.+-]+Z?\\b");
    private static final Pattern STACK_LINE = Pattern.compile(":\\d+\\)");
    private static final Pattern ELAPSED = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?\\s*(?:ms|s|sec|secs)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY_HASH = Pattern.compile(
            "(?:@|0x)[0-9a-f]{6,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    /** GitHub Actions prefixes every raw log line with an RFC3339 timestamp. Left in
     * place it defeats every {@code startsWith} test below, including the
     * {@code Caused by:} root-cause walk, so the signature degrades to the outer
     * wrapper line. Stripped per line, not globally: a timestamp appearing mid-line
     * is log content and normalization scrubs it instead. */
    private static final Pattern ACTIONS_LINE_PREFIX = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s");
    /** Surefire's end-of-run recap entry, e.g. {@code [ERROR]   TestFoo.testBar:83}.
     * The two-space indent is what separates a test entry from the goal-level
     * {@code [ERROR] Failed to execute goal…} lines printed beside it. */
    private static final Pattern SUREFIRE_RECAP = Pattern.compile(
            "^\\[ERROR]\\s{2,}([\\w$.]*[A-Z][\\w$]*)\\.([\\w$]+)(?::\\d+)?\\s*(»)?\\s*(.*)$");
    /** How far a failure's excerpt runs, for a surefire section and for the
     * generic scan alike. A Maven failure states the problem over a dozen lines
     * and then spends thirty on how to re-run it; a stack with suppressed causes
     * is longer still. Cutting at the old forty lost the end of both. */
    private static final int MAX_DETAIL_LINES = 80;
    /** Enough to catch the command that failed, and the goal or module heading
     * above it, without dragging in the previous step's output. */
    private static final int CONTEXT_BEFORE = 6;
    /** The start of a new log record: a build tool's level tag, or the timestamp a
     * test suite's own logger opens every line with. */
    private static final Pattern RECORD_START = Pattern.compile(
            "^(?:\\[(?:INFO|ERROR|WARNING|DEBUG)]|WARNING: |\\d{4}-\\d{2}-\\d{2}[T ])");
    private static final Pattern MAVEN_TEST = Pattern.compile("(?:\\[ERROR]\\s+)?([\\w.$]+)(?:#|\\.)([\\w$]+).*?(?:FAILURE|ERROR)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PYTEST_TEST = Pattern.compile("([\\w/.-]+\\.py)::([\\w.-]+)");
    private static final Pattern PATH = Pattern.compile("(?:^|\\s)([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)(?::\\d+)?");

    public List<ParsedFailure> parse(String runId, long checkRunId, String jobName, String log, BootstrapProfile profile)
    {
        if (log == null || log.isBlank()) {
            return List.of();
        }
        String clean = ANSI.matcher(log).replaceAll("");
        List<String> lines = clean.lines()
                .map(line -> ACTIONS_LINE_PREFIX.matcher(line).replaceFirst(""))
                .toList();
        List<ParsedFailure> surefire = surefireFailures(runId, checkRunId, jobName, lines, profile);
        if (!surefire.isEmpty()) {
            // The suite said which tests failed, so that is the answer. The
            // generic scan below would bury it: a teardown storm mints one
            // "failure" per worker task and drowns the three that matter.
            return surefire;
        }
        Map<String, ParsedFailure> deduped = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!isFailureLine(line) || isContinuation(lines, i)) {
                continue;
            }
            int from = Math.max(0, i - CONTEXT_BEFORE);
            int to = Math.min(lines.size(), i + MAX_DETAIL_LINES);
            String excerpt = String.join("\n", lines.subList(from, to));
            String cause = rootCause(lines, i, to);
            String signature = normalize(cause == null ? line : cause);
            if (signature.isBlank()) {
                continue;
            }
            TestId test = testId(excerpt);
            String module = moduleOf(excerpt, profile);
            ParsedFailure failure = new ParsedFailure(
                    runId, checkRunId, jobName, module, test.className(), test.method(),
                    signature, excerpt.length() > 12_000 ? excerpt.substring(0, 12_000) : excerpt);
            deduped.putIfAbsent(signature, failure);
        }
        if (deduped.isEmpty()) {
            String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
            String signature = normalize(lastMeaningfulLine(lines));
            if (!signature.isBlank()) {
                deduped.put(signature, new ParsedFailure(
                        runId, checkRunId, jobName, moduleOf(tail, profile),
                        null, null, signature, tail));
            }
        }
        return List.copyOf(deduped.values());
    }

    /**
     * Surefire's own verdict, read off its end-of-run recap and expanded back
     * into the per-test section the recap summarises.
     *
     * <p>The recap is a one-line-per-test list with the assertion message but no
     * stack; the section printed when the test actually failed has the stack and
     * the suppressed causes. They sit thousands of lines apart, so the recap
     * entry's {@code Class.method} is used to find the section again.
     */
    private List<ParsedFailure> surefireFailures(
            String runId, long checkRunId, String jobName, List<String> lines, BootstrapProfile profile)
    {
        Map<String, ParsedFailure> deduped = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher recap = SUREFIRE_RECAP.matcher(lines.get(i));
            if (!recap.matches()) {
                continue;
            }
            String className = recap.group(1);
            String method = recap.group(2);
            String message = message(recap.group(4), lines, i);
            String signature = normalize(simpleName(className) + "." + method + " " + message);
            if (signature.isBlank()) {
                continue;
            }
            String excerpt = detail(lines, simpleName(className), method);
            if (excerpt == null) {
                // The section was cut or never printed — the recap entry and its
                // message are still a real, actionable failure on their own.
                excerpt = String.join("\n", lines.subList(i, Math.min(lines.size(), i + 6)));
            }
            deduped.putIfAbsent(signature, new ParsedFailure(
                    runId, checkRunId, jobName, moduleOf(excerpt, profile), className, method,
                    signature, excerpt.length() > 12_000 ? excerpt.substring(0, 12_000) : excerpt));
        }
        return List.copyOf(deduped.values());
    }

    /**
     * The recap entry's message, which surefire wraps onto the lines after it
     * ({@code expected: 1} / {@code but was: 0}) as often as it keeps it inline.
     */
    private static String message(String inline, List<String> lines, int entry)
    {
        StringBuilder text = new StringBuilder(inline == null ? "" : inline.strip());
        for (int i = entry + 1; i < Math.min(lines.size(), entry + 6); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("[ERROR]") || line.startsWith("[INFO]") || line.startsWith("[WARNING]")) {
                break;
            }
            if (!line.isBlank()) {
                text.append(text.isEmpty() ? "" : " ").append(line);
            }
        }
        return text.toString();
    }

    /**
     * The block surefire printed when the test failed: its header line, the
     * throwable, its stack, and anything suppressed. Ends at the next test's
     * header or the next reactor line.
     */
    private static String detail(List<String> lines, String simpleClass, String method)
    {
        // A test that fails in its own body is reported per method; one that fails
        // building the class — a factory, a @BeforeAll — is reported against the
        // class alone, even though the recap still names the method that called
        // it. Both trailing spaces matter: they stop TestFoo matching TestFooBar.
        String section = section(lines, simpleClass + "." + method + " ");
        return section != null ? section : section(lines, simpleClass + " ");
    }

    private static String section(List<String> lines, String needle)
    {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("[ERROR] ") || !line.contains("<<< ") || !line.contains(needle)) {
                continue;
            }
            int to = Math.min(lines.size(), i + MAX_DETAIL_LINES);
            for (int end = i + 1; end < to; end++) {
                String candidate = lines.get(end);
                if (candidate.startsWith("[INFO]")
                        || (candidate.startsWith("[ERROR] ") && candidate.contains("<<< "))
                        || endsSection(lines, end, to)) {
                    to = end;
                    break;
                }
            }
            return String.join("\n", lines.subList(i, to));
        }
        return null;
    }

    /**
     * A blank line ends the section only when what follows it starts a new log
     * record. Inside a throwable, blank lines separate the message from the
     * assertion text and the suppressed causes — cutting there would drop the
     * stack the section exists for. Without this a class-level section runs on
     * into whatever the suite logged next, to the line cap.
     */
    private static boolean endsSection(List<String> lines, int at, int to)
    {
        if (!lines.get(at).isBlank()) {
            return false;
        }
        int next = at + 1;
        while (next < to && lines.get(next).isBlank()) {
            next++;
        }
        return next < to && RECORD_START.matcher(lines.get(next)).find();
    }

    private static String simpleName(String className)
    {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    static String normalize(String value)
    {
        String out = value == null ? "" : value;
        out = RANDOM_SUFFIX.matcher(out).replaceAll("");
        out = TEMP_PATH.matcher(out).replaceAll("<tmp>");
        out = TIMESTAMP.matcher(out).replaceAll("<ts>");
        out = STACK_LINE.matcher(out).replaceAll(":<n>)");
        // Both vary per run and appear on essentially every surefire failure line.
        // Unscrubbed they break the dedupe key and the KB matcher at once, so a
        // learned rule can never accumulate the hits it needs to graduate.
        out = ELAPSED.matcher(out).replaceAll("<dur>");
        out = IDENTITY_HASH.matcher(out).replaceAll("<addr>");
        out = out.replaceAll("\\s+", " ").strip();
        return out.substring(0, Math.min(out.length(), 200));
    }

    private static boolean isFailureLine(String line)
    {
        if (line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("warning") || lower.contains("warn:") || lower.contains("deprecated")) {
            return false;
        }
        return lower.startsWith("caused by:")
                || lower.startsWith("error:")
                || lower.startsWith("e   ")
                // Maven and the Actions runner mark severity with a bracket tag and
                // never write "error:", so a build that dies outside a test or a
                // goal — an unparseable pom, an unresolvable dependency — used to
                // match nothing at all and fall back to the tail of the log.
                || isTagged(lower)
                || lower.contains("<<< failure")
                || lower.contains("assertionerror")
                || lower.contains("compilation failure")
                || lower.contains("build failure")
                || lower.matches(".*(?:exception|error): .+")
                || lower.matches(".*\\bfailed\\b.*");
    }

    /**
     * Whether a tagged line is the continuation of the tagged line above it.
     * Maven writes one failure as a run of {@code [ERROR]} lines — the enforcer
     * spends twenty-five on a single rule — and every one of them reads as a
     * failure on its own. Only the line that opens a run is the failure.
     */
    private static boolean isContinuation(List<String> lines, int at)
    {
        if (!isTagged(lines.get(at).strip().toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (int i = at - 1; i >= 0; i--) {
            String previous = lines.get(i).strip();
            if (!previous.isBlank()) {
                return isTagged(previous.toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }

    private static boolean isTagged(String lowerCaseLine)
    {
        return lowerCaseLine.startsWith("[error]")
                || lowerCaseLine.startsWith("[fatal]")
                || lowerCaseLine.startsWith("##[error]");
    }

    private static String rootCause(List<String> lines, int failureLine, int to)
    {
        String root = lines.get(failureLine).strip();
        for (int i = failureLine; i < to; i++) {
            String candidate = lines.get(i).strip();
            if (candidate.toLowerCase(Locale.ROOT).startsWith("caused by:")) {
                root = candidate;
            }
        }
        return root;
    }

    private static String lastMeaningfulLine(List<String> lines)
    {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (!line.isBlank() && !line.toLowerCase(Locale.ROOT).contains("warning")) {
                return line;
            }
        }
        return "";
    }

    private static TestId testId(String excerpt)
    {
        Matcher maven = MAVEN_TEST.matcher(excerpt);
        if (maven.find()) {
            return new TestId(maven.group(1), maven.group(2));
        }
        Matcher pytest = PYTEST_TEST.matcher(excerpt);
        if (pytest.find()) {
            return new TestId(pytest.group(1), pytest.group(2));
        }
        return new TestId(null, null);
    }

    private static String moduleOf(String excerpt, BootstrapProfile profile)
    {
        List<Map.Entry<String, String>> modules = new ArrayList<>(profile.modules().entrySet());
        modules.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        String normalized = excerpt.replace('\\', '/');
        for (Map.Entry<String, String> entry : modules) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        Matcher path = PATH.matcher(normalized);
        if (path.find()) {
            String value = path.group(1);
            int slash = value.indexOf('/');
            return slash > 0 ? value.substring(0, slash) : "root";
        }
        return "root";
    }

    public record ParsedFailure(
            String runId,
            long checkRunId,
            String jobName,
            String module,
            String testClass,
            String testMethod,
            String signature,
            String logExcerpt) {}

    private record TestId(String className, String method) {}
}
