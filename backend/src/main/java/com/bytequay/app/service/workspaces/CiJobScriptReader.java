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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
final class CiJobScriptReader
{
    private static final Logger log = LoggerFactory.getLogger(CiJobScriptReader.class);
    private static final String WORKFLOWS = ".github/workflows";
    private static final String RUNNABLE = "^(?:\\./mvnw|mvn)(?:\\s+[A-Za-z0-9_@%+=:,./~-]+)*$";

    private CiJobScriptReader() {}

    /**
     * Locates the build command for one job.
     *
     * @param jobName the job's id or its {@code name:}, matched case-insensitively
     */
    static Optional<String> buildScript(Path repoRoot, String jobName)
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
