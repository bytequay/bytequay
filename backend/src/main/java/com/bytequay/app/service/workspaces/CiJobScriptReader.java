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
package com.bytequay.app.service.workspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Reads the build command out of one named CI job.
 *
 * <p>Deliberately deterministic rather than model-driven: locating a {@code run:}
 * line under a named job is parsing, and a parser cannot hallucinate a command
 * that will then be executed. Only a bare build invocation is accepted, so a job
 * whose script is a shell pipeline is reported as not-found and the caller falls
 * back rather than running something it cannot vet.
 */
public final class CiJobScriptReader
{
    private static final Logger log = LoggerFactory.getLogger(CiJobScriptReader.class);
    private static final String WORKFLOWS = ".github/workflows";
    private static final String RUNNABLE = "^(?:\\./mvnw|mvn)(?:\\s+[A-Za-z0-9_@%+=:,./~-]+)*$";

    private CiJobScriptReader() {}

    /** One command together with the exact workflow location that owns it. */
    public record BuildInvocation(
            String command,
            String workingDirectory,
            String jobName,
            String sourceRef,
            String sourceDigest)
    {
        public BuildInvocation
        {
            if (command == null || command.isBlank()
                    || workingDirectory == null
                    || workingDirectory.isBlank()
                    || jobName == null || jobName.isBlank()
                    || sourceRef == null || sourceRef.isBlank()
                    || sourceDigest == null || sourceDigest.isBlank()) {
                throw new IllegalArgumentException(
                        "build invocation fields are blank");
            }
        }

        public List<String> arguments()
        {
            return List.of(command.strip().split("\\s+"));
        }
    }

    /**
     * Locates the build command for one job.
     *
     * @param jobName the job's id or its {@code name:}, matched case-insensitively
     */
    public static Optional<String> buildScript(Path repoRoot, String jobName)
    {
        if (jobName == null || jobName.isBlank()) {
            return Optional.empty();
        }
        Path dir = repoRoot.resolve(WORKFLOWS);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .map(file -> scriptIn(file, jobName.strip()))
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        catch (IOException e) {
            log.warn("unable to read CI workflows under {}", dir, e);
            return Optional.empty();
        }
    }

    /**
     * The build command CI itself runs, with no job named: the first plain
     * build invocation in any workflow, files in name order.
     *
     * <p>This is how the run learns to compile. It stays a parser rather than a
     * question for the model for the same reason {@link #buildScript} does — the
     * answer is executed, so it must be read out of the repository rather than
     * generated. A pipeline, a wrapper script or anything with shell syntax is
     * skipped, and the caller falls back to a plain compile.
     */
    public static Optional<String> anyBuildScript(Path repoRoot)
    {
        Path dir = repoRoot.resolve(WORKFLOWS);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .map(CiJobScriptReader::firstBuildCommand)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        catch (IOException e) {
            log.warn("unable to read CI workflows under {}", dir, e);
            return Optional.empty();
        }
    }

    /**
     * Returns the first safe build invocation and its workflow-owned working
     * directory. The older string-only API remains for the legacy worker.
     */
    public static Optional<BuildInvocation> anyBuildInvocation(Path repoRoot)
    {
        return anyInvocation(repoRoot, ignored -> true);
    }

    /** First safe Maven invocation that actually executes tests. */
    public static Optional<BuildInvocation> anyTestInvocation(Path repoRoot)
    {
        return anyInvocation(repoRoot, CiJobScriptReader::runsTests);
    }

    private static Optional<BuildInvocation> anyInvocation(
            Path repoRoot, Predicate<String> accepted)
    {
        Path dir = repoRoot.resolve(WORKFLOWS);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(CiJobScriptReader::workflowFile)
                    .sorted()
                    .map(file -> invocationIn(repoRoot, file, accepted))
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        catch (IOException e) {
            log.warn("unable to read CI workflows under {}", dir, e);
            return Optional.empty();
        }
    }

    private static Optional<BuildInvocation> invocationIn(
            Path repoRoot,
            Path workflow,
            Predicate<String> accepted)
    {
        byte[] source;
        try {
            source = Files.readAllBytes(workflow);
        }
        catch (IOException failure) {
            return Optional.empty();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(40);
            options.setCodePointLimit(1_000_000);
            Object loaded = new Yaml(new SafeConstructor(options)).load(
                    new String(source, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> root)
                    || !(root.get("jobs") instanceof Map<?, ?> jobs)) {
                return Optional.empty();
            }
            String rootDirectory = workingDirectory(root.get("defaults"), ".");
            for (Map.Entry<?, ?> entry : jobs.entrySet()) {
                if (!(entry.getKey() instanceof String jobId)
                        || !(entry.getValue() instanceof Map<?, ?> job)) {
                    return Optional.empty();
                }
                String jobName = text(job.get("name")).orElse(jobId);
                String jobDirectory = workingDirectory(
                        job.get("defaults"), rootDirectory);
                if (!(job.get("steps") instanceof List<?> steps)) {
                    continue;
                }
                for (Object value : steps) {
                    if (!(value instanceof Map<?, ?> step)) {
                        return Optional.empty();
                    }
                    Optional<String> command = plainBuildCommand(
                            step.get("run"));
                    if (command.isEmpty()
                            || !accepted.test(command.orElseThrow())) {
                        continue;
                    }
                    String directory = text(step.get("working-directory"))
                            .orElse(jobDirectory);
                    Path normalized = Path.of(directory).normalize();
                    if (normalized.isAbsolute()
                            || normalized.startsWith("..")
                            || directory.contains("${{")) {
                        return Optional.empty();
                    }
                    return Optional.of(new BuildInvocation(
                            command.orElseThrow(),
                            normalized.toString().isEmpty()
                                    ? "." : normalized.toString(),
                            jobName,
                            repoRoot.relativize(workflow).toString(),
                            "sha256:" + sha256(source)));
                }
            }
            return Optional.empty();
        }
        catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String workingDirectory(Object defaults, String fallback)
    {
        if (!(defaults instanceof Map<?, ?> defaultMap)
                || !(defaultMap.get("run") instanceof Map<?, ?> run)) {
            return fallback;
        }
        return text(run.get("working-directory")).orElse(fallback);
    }

    private static Optional<String> plainBuildCommand(Object value)
    {
        return text(value).stream()
                .flatMap(String::lines)
                .map(String::strip)
                .filter(command -> command.matches(RUNNABLE))
                .findFirst();
    }

    private static boolean runsTests(String command)
    {
        List<String> arguments = List.of(command.strip().split("\\s+"));
        boolean skips = arguments.stream().map(value -> value.toLowerCase(
                        Locale.ROOT))
                .anyMatch(value -> value.equals("-dskiptests")
                        || value.equals("-dskiptests=true")
                        || value.equals("-dmaven.test.skip=true"));
        return !skips && arguments.stream().anyMatch(value ->
                value.equals("test") || value.equals("verify")
                        || value.equals("integration-test"));
    }

    private static Optional<String> text(Object value)
    {
        return value instanceof String text && !text.isBlank()
                ? Optional.of(text.strip()) : Optional.empty();
    }

    private static boolean workflowFile(Path file)
    {
        String name = file.getFileName().toString()
                .toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static String sha256(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Optional<String> firstBuildCommand(Path workflow)
    {
        List<String> lines;
        try {
            lines = Files.readAllLines(workflow, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return Optional.empty();
        }
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Optional<String> found = runCommand(
                    lines, i, trimmed, indentation(lines.get(i)));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> scriptIn(Path workflow, String jobName)
    {
        List<String> lines;
        try {
            lines = Files.readAllLines(workflow, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return Optional.empty();
        }
        boolean inJobs = false;
        int jobIndent = -1;
        boolean inTarget = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = indentation(raw);
            if (!inJobs) {
                inJobs = trimmed.startsWith("jobs:");
                continue;
            }
            boolean isJobHeader = trimmed.endsWith(":")
                    && !trimmed.contains(" ")
                    && (jobIndent < 0 || indent == jobIndent);
            if (isJobHeader) {
                jobIndent = indent;
                String id = trimmed.substring(0, trimmed.length() - 1).strip();
                inTarget = id.equalsIgnoreCase(jobName);
                continue;
            }
            if (jobIndent >= 0 && indent <= jobIndent && !isJobHeader) {
                // Left the jobs block entirely.
                if (indent < jobIndent) {
                    break;
                }
            }
            if (!inTarget) {
                // A job's display name can also identify it.
                if (trimmed.startsWith("name:") && indent == jobIndent + 2) {
                    inTarget = scalar(trimmed.substring("name:".length())).equalsIgnoreCase(jobName);
                }
                continue;
            }
            Optional<String> found = runCommand(lines, i, trimmed, indent);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> runCommand(
            List<String> lines, int index, String trimmed, int indent)
    {
        String step = trimmed.startsWith("- ") ? trimmed.substring(2).stripLeading() : trimmed;
        if (!step.startsWith("run:")) {
            return Optional.empty();
        }
        String command = trimmed.substring(trimmed.indexOf(':') + 1).strip();
        if ("|".equals(command) || ">".equals(command) || command.isEmpty()) {
            // A block scalar holds one command per line; take the first that is a
            // plain build invocation and ignore setup lines around it.
            for (int i = index + 1; i < lines.size() && indentation(lines.get(i)) > indent; i++) {
                String candidate = lines.get(i).strip();
                if (candidate.matches(RUNNABLE)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
        return command.matches(RUNNABLE) ? Optional.of(command) : Optional.empty();
    }

    private static String scalar(String value)
    {
        String stripped = value.strip();
        if (stripped.length() >= 2
                && (stripped.startsWith("\"") && stripped.endsWith("\"")
                || stripped.startsWith("'") && stripped.endsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static int indentation(String line)
    {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }
}
