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
    private static final Pattern RANDOM_SUFFIX = Pattern.compile("_[0-9a-f]{6,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMP_PATH = Pattern.compile("(?:/tmp|/private/tmp|[A-Za-z]:\\\\Temp)[/\\\\]\\S+");
    private static final Pattern TIMESTAMP = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}T[\\d:.+-]+Z?\\b");
    private static final Pattern STACK_LINE = Pattern.compile(":\\d+\\)");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final Pattern MAVEN_TEST = Pattern.compile("(?:\\[ERROR]\\s+)?([\\w.$]+)(?:#|\\.)([\\w$]+).*?(?:FAILURE|ERROR)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PYTEST_TEST = Pattern.compile("([\\w/.-]+\\.py)::([\\w.-]+)");
    private static final Pattern PATH = Pattern.compile("(?:^|\\s)([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)(?::\\d+)?");

    public List<ParsedFailure> parse(String runId, long checkRunId, String jobName, String log, BootstrapProfile profile)
    {
        if (log == null || log.isBlank()) {
            return List.of();
        }
        String clean = ANSI.matcher(log).replaceAll("");
        List<String> lines = clean.lines().toList();
        Map<String, ParsedFailure> deduped = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!isFailureLine(line)) {
                continue;
            }
            int from = Math.max(0, i - 2);
            int to = Math.min(lines.size(), i + 41);
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

    static String normalize(String value)
    {
        String out = value == null ? "" : value;
        out = RANDOM_SUFFIX.matcher(out).replaceAll("");
        out = TEMP_PATH.matcher(out).replaceAll("<tmp>");
        out = TIMESTAMP.matcher(out).replaceAll("<ts>");
        out = STACK_LINE.matcher(out).replaceAll(":<n>)");
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
                || lower.contains("<<< failure")
                || lower.contains("assertionerror")
                || lower.contains("compilation failure")
                || lower.contains("build failure")
                || lower.matches(".*(?:exception|error): .+")
                || lower.matches(".*\\bfailed\\b.*");
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
