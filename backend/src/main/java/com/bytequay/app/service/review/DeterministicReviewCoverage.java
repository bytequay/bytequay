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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, zero-model coverage floor for every investigation round. */
final class DeterministicReviewCoverage
{
    static final int MAX_SWEEP_CANDIDATES = 8;

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$");
    private static final Pattern METHOD = Pattern.compile(
            "(?:function|def|fun|func)\\s+([A-Za-z_$][\\w$]*)\\s*\\(|"
                    + "(?:public|protected|private|static|final|abstract|synchronized|async|export|override|open|internal|native|\\s)+"
                    + "[A-Za-z_$][\\w$<>, ?\\[\\].]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern LOGIC = Pattern.compile(
            "\\b(if|else|switch|case|return|throw|catch|finally)\\b|"
                    + "==|!=|<=|>=|&&|\\|\\||\\?\\?|\\b(null|undefined|optional)\\b",
            Pattern.CASE_INSENSITIVE);

    private DeterministicReviewCoverage() {}

    static CoverageReport analyze(String diff)
    {
        return analyze(diff, (Function<String, List<String>>) null);
    }

    static CoverageReport analyze(
            String diff, InvestigationReviewContext context,
            InvestigationReviewContext.Snapshot snapshot)
    {
        Function<String, List<String>> references = snapshot.fileContents().isEmpty()
                ? null : symbol -> context.repositoryReferences(snapshot, symbol, 21);
        return analyze(diff, references);
    }

    private static CoverageReport analyze(
            String diff, Function<String, List<String>> repositoryReferences)
    {
        Patch patch = parse(diff == null ? "" : diff);
        List<SweepResult> sweeps = List.of(
                lineScan(patch),
                removedBehavior(patch),
                crossFileTrace(patch, repositoryReferences),
                languagePitfalls(patch),
                extractionCorrectness(patch));
        return new CoverageReport(sweeps, failureClasses(patch));
    }

    private static SweepResult lineScan(Patch patch)
    {
        List<String> candidates = new ArrayList<>();
        for (Hunk hunk : patch.hunks()) {
            for (ChangedLine line : hunk.lines()) {
                if (LOGIC.matcher(line.text()).find()) {
                    addCandidate(candidates, line.location() + " — boundary/control-flow change — "
                            + compact(line.text()));
                }
            }
        }
        return new SweepResult("line-scan", !patch.hunks().isEmpty(), true, patch.hunks().size(),
                candidates, "Inspected every changed hunk and its enclosing hunk context.");
    }

    private static SweepResult removedBehavior(Patch patch)
    {
        List<String> candidates = new ArrayList<>();
        int removed = 0;
        for (ChangedLine line : patch.changedLines()) {
            if (line.kind() != ChangeKind.REMOVED || line.text().isBlank()) {
                continue;
            }
            removed++;
            addCandidate(candidates, line.location() + " — identify the invariant formerly enforced — "
                    + compact(line.text()));
        }
        return new SweepResult("removed-behavior", removed > 0, true, removed, candidates,
                "Inspected every deleted or replaced non-blank line.");
    }

    private static SweepResult crossFileTrace(
            Patch patch, Function<String, List<String>> repositoryReferences)
    {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (ChangedLine line : patch.changedLines()) {
            Matcher matcher = METHOD.matcher(line.text());
            if (matcher.find()) {
                symbols.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
            }
        }
        List<String> candidates = new ArrayList<>();
        for (String symbol : symbols) {
            List<String> references = repositoryReferences == null
                    ? patch.referenceLocations(symbol, 21)
                    : repositoryReferences.apply(symbol);
            String fanIn = references.size() > 20 ? "HIGH_FAN_IN; use a blast-radius hypothesis"
                    : references.size() + " bounded static references";
            String sample = references.stream().limit(3).reduce("", (left, right) ->
                    left.isEmpty() ? right : left + ", " + right);
            addCandidate(candidates, symbol + " — trace direct callers/callees and payload consumers — "
                    + fanIn + (sample.isEmpty() ? "" : " — " + sample));
        }
        boolean covered = repositoryReferences != null || symbols.isEmpty();
        return new SweepResult("cross-file-trace", !symbols.isEmpty(), covered, symbols.size(), candidates,
                repositoryReferences == null && !symbols.isEmpty()
                        ? "Frozen changed-file bodies are unavailable; patch references were recorded and the gap remains explicit."
                        : "Enumerated modified function symbols and bounded references within frozen changed-file bodies at 20 per symbol.");
    }

    private static SweepResult languagePitfalls(Patch patch)
    {
        Set<String> languages = patch.languages();
        List<String> candidates = new ArrayList<>();
        String lower = patch.changedText().toLowerCase(Locale.ROOT);
        if (languages.contains("java") && lower.contains("@transactional") && lower.contains("this.")) {
            addCandidate(candidates, "Java — @Transactional self-invocation may bypass proxy boundaries");
        }
        if (languages.contains("java") && lower.contains("optional.get(")) {
            addCandidate(candidates, "Java — Optional.get() requires a proven presence path");
        }
        if ((languages.contains("javascript") || languages.contains("typescript"))
                && patch.changedLines().stream().anyMatch(line -> line.text().contains("||"))) {
            addCandidate(candidates, "JavaScript/TypeScript — check || defaulting against intentional nullish semantics");
        }
        if ((languages.contains("javascript") || languages.contains("typescript"))
                && lower.contains("useeffect(")) {
            addCandidate(candidates, "React — check changed useEffect dependencies and stale closure behavior");
        }
        if (languages.contains("python") && Pattern.compile("def\\s+\\w+\\([^)]*=\\s*(\\[|\\{)")
                .matcher(patch.changedText()).find()) {
            addCandidate(candidates, "Python — mutable default argument changed");
        }
        if (languages.contains("kotlin") && patch.changedText().contains("!!")) {
            addCandidate(candidates, "Kotlin — non-null assertion requires a proven invariant");
        }
        return new SweepResult("language-pitfall", !languages.isEmpty(), true, languages.size(), candidates,
                languages.isEmpty() ? "No source language was detected."
                        : "Applied shipped checklists for " + String.join(", ", languages) + ".");
    }

    private static SweepResult extractionCorrectness(Patch patch)
    {
        Map<String, ChangedLine> removed = new LinkedHashMap<>();
        for (ChangedLine line : patch.changedLines()) {
            String normal = normalizedCode(line.text());
            if (line.kind() == ChangeKind.REMOVED && normal.length() >= 16) {
                removed.putIfAbsent(normal, line);
            }
        }
        List<String> candidates = new ArrayList<>();
        for (ChangedLine added : patch.changedLines()) {
            if (added.kind() != ChangeKind.ADDED) {
                continue;
            }
            ChangedLine old = removed.get(normalizedCode(added.text()));
            if (old != null && !old.path().equals(added.path())) {
                addCandidate(candidates, old.path() + " → " + added.path()
                        + " — re-check transaction/lock boundaries, error propagation, and double-fire state");
            }
        }
        return new SweepResult("extraction-correctness", !candidates.isEmpty(), true, candidates.size(), candidates,
                candidates.isEmpty() ? "No cross-file code move was detected."
                        : "Detected code moved between files and queued boundary checks.");
    }

    private static List<FailureClassResult> failureClasses(Patch patch)
    {
        String text = patch.changedText().toLowerCase(Locale.ROOT);
        boolean source = patch.languages().stream().anyMatch(language -> !"other".equals(language));
        return List.of(
                failure("logic-boundary", source && LOGIC.matcher(text).find(),
                        "changed control flow, boundary checks, nullability, or return behavior"),
                failure("removed-behavior", patch.changedLines().stream()
                                .anyMatch(line -> line.kind() == ChangeKind.REMOVED && !line.text().isBlank()),
                        "deleted or replaced behavior"),
                failure("interface-contract", containsAny(text,
                                "public ", "interface ", " api", "spi", "schema", "protocol", "serialized", "config"),
                        "public/API/SPI/schema/config/serialized construct"),
                failure("state-lifecycle", containsAny(text,
                                " state", "status", "start", "stop", "open", "close", "lifecycle", "transition"),
                        "state transition or lifecycle construct"),
                failure("concurrency", containsAny(text,
                                "synchronized", "async", "atomic", "thread", "executor", "queue", "lock", "concurrent"),
                        "synchronization, async work, shared state, queue, or lock"),
                failure("resource-handling", containsAny(text,
                                "closeable", "close(", "stream", "transaction", "resource", "socket", "file"),
                        "closeable, stream, transaction, or resource path"),
                failure("error-handling", containsAny(text,
                                "try", "catch", "throw", "exception", "error", "failure", "result"),
                        "exception, failure, or error propagation path"),
                failure("security", containsAny(text,
                                "auth", "token", "credential", "permission", "security", "secret", "sanitize"),
                        "authentication, authorization, credential, secret, or sanitization path"),
                failure("compatibility", containsAny(text,
                                "public ", " api", "spi", "schema", "config", "serializ", "protocol", "migration"),
                        "externally consumed API, schema, config, serialization, protocol, or migration"),
                failure("data-integrity", containsAny(text,
                                "database", "repository", " sql", "save(", "delete(", "update(", "transaction", "json"),
                        "persistence, transaction, mutation, or serialized data path"));
    }

    private static FailureClassResult failure(String id, boolean applicable, String reason)
    {
        return new FailureClassResult(id, applicable,
                applicable ? "Applicable: " + reason + "." : "Not applicable: no " + reason + " detected.");
    }

    private static Patch parse(String diff)
    {
        List<Hunk> hunks = new ArrayList<>();
        List<ChangedLine> changed = new ArrayList<>();
        String path = "(unknown)";
        Hunk current = null;
        int oldLine = 0;
        int newLine = 0;
        for (String raw : diff.split("\\R", -1)) {
            Matcher file = DIFF_HEADER.matcher(raw);
            if (file.matches()) {
                path = file.group(2);
                current = null;
                continue;
            }
            Matcher header = HUNK_HEADER.matcher(raw);
            if (header.matches()) {
                oldLine = Integer.parseInt(header.group(1));
                newLine = Integer.parseInt(header.group(2));
                current = new Hunk(path, newLine, header.group(3).strip(), new ArrayList<>());
                hunks.add(current);
                continue;
            }
            if (current == null || raw.startsWith("\\ No newline")) {
                continue;
            }
            if (raw.startsWith("+") && !raw.startsWith("+++")) {
                ChangedLine line = new ChangedLine(path, null, newLine++, ChangeKind.ADDED,
                        raw.substring(1), current.newStart());
                current.lines().add(line);
                changed.add(line);
            }
            else if (raw.startsWith("-") && !raw.startsWith("---")) {
                ChangedLine line = new ChangedLine(path, oldLine++, null, ChangeKind.REMOVED,
                        raw.substring(1), current.newStart());
                current.lines().add(line);
                changed.add(line);
            }
            else {
                oldLine++;
                newLine++;
            }
        }
        return new Patch(List.copyOf(hunks), List.copyOf(changed));
    }

    private static void addCandidate(List<String> candidates, String candidate)
    {
        if (candidates.size() < MAX_SWEEP_CANDIDATES && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static boolean containsAny(String text, String... needles)
    {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedCode(String value)
    {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String compact(String value)
    {
        String compact = normalizedCode(value);
        return compact.substring(0, Math.min(180, compact.length()));
    }

    record CoverageReport(List<SweepResult> sweeps, List<FailureClassResult> failureClasses)
    {
        String promptContext()
        {
            StringBuilder out = new StringBuilder("Deterministic coverage floor (inspect and deepen; do not cite this summary as evidence):\n");
            for (SweepResult sweep : sweeps) {
                out.append("- ").append(sweep.name()).append(": ")
                        .append(!sweep.applicable() ? "not applicable"
                                : sweep.covered() ? "completed" : "not covered")
                        .append("; inspected ").append(sweep.inspectedUnits()).append("; ")
                        .append(sweep.note()).append('\n');
                sweep.candidates().forEach(candidate -> out.append("  candidate: ").append(candidate).append('\n'));
            }
            out.append("Failure-class dispositions owed:\n");
            failureClasses.stream().filter(FailureClassResult::applicable)
                    .forEach(result -> out.append("- ").append(result.id()).append(": ")
                            .append(result.reason()).append('\n'));
            return out.toString();
        }
    }

    record SweepResult(
            String name, boolean applicable, boolean covered, int inspectedUnits,
            List<String> candidates, String note)
    {
        SweepResult
        {
            candidates = List.copyOf(candidates);
        }

        String preview()
        {
            StringBuilder out = new StringBuilder(note).append('\n');
            if (candidates.isEmpty()) {
                out.append("No candidates emitted; no padding.\n");
            }
            else {
                candidates.forEach(candidate -> out.append("- ").append(candidate).append('\n'));
            }
            return out.toString();
        }
    }

    record FailureClassResult(String id, boolean applicable, String reason) {}

    private record Patch(List<Hunk> hunks, List<ChangedLine> changedLines)
    {
        String changedText()
        {
            return changedLines.stream().map(ChangedLine::text).reduce("", (left, right) -> left + "\n" + right);
        }

        List<String> referenceLocations(String symbol, int limit)
        {
            Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(symbol) + "(?![\\w$])");
            List<String> matches = new ArrayList<>();
            for (ChangedLine line : changedLines) {
                Matcher matcher = word.matcher(line.text());
                if (matcher.find()) {
                    matches.add(line.location());
                    if (matches.size() >= limit) {
                        break;
                    }
                }
            }
            return List.copyOf(matches);
        }

        Set<String> languages()
        {
            LinkedHashSet<String> languages = new LinkedHashSet<>();
            for (ChangedLine line : changedLines) {
                String lower = line.path().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".java")) {
                    languages.add("java");
                }
                else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
                    languages.add("kotlin");
                }
                else if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                    languages.add("typescript");
                }
                else if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
                    languages.add("javascript");
                }
                else if (lower.endsWith(".py")) {
                    languages.add("python");
                }
                else if (lower.endsWith(".go")) {
                    languages.add("go");
                }
                else if (lower.endsWith(".rs")) {
                    languages.add("rust");
                }
            }
            return Set.copyOf(languages);
        }
    }

    private record Hunk(String path, int newStart, String context, List<ChangedLine> lines) {}

    private record ChangedLine(
            String path, Integer oldLine, Integer newLine, ChangeKind kind,
            String text, int hunkStart)
    {
        String location()
        {
            return path + ":" + (newLine == null ? oldLine : newLine);
        }
    }

    private enum ChangeKind { ADDED, REMOVED }
}
