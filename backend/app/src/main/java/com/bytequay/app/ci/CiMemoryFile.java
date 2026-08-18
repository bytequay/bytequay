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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixRun;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

@Component
final class CiMemoryFile
{
    private static final String FILE_NAME = "ci-memory.md";
    private static final String FAILURE_PREFIX = "## Failure ";
    private static final String UNAVAILABLE = "CI memory is unavailable. Continue with the current CI logs and repository.";
    private static final String NO_MATCH = "No CI memory matched the query. Continue with the current CI logs and repository.";
    private static final int MAX_SIGNATURES = 50;
    private static final int MAX_ATTEMPTS_PER_SIGNATURE = 5;
    private static final int MAX_QUERY_CHARACTERS = 200;
    private static final int MAX_READ_SECTIONS = 10;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_RESULT_BYTES = 32 * 1024;
    private static final Pattern SECTION_BOUNDARY = Pattern.compile("(?m)(?=^## Failure )");

    private final AgentResourceService resources;
    private final MemoryFileWriter fileWriter;

    @Autowired
    CiMemoryFile(AgentResourceService resources)
    {
        this(resources, CiMemoryFile::replaceAtomically);
    }

    CiMemoryFile(AgentResourceService resources, MemoryFileWriter fileWriter)
    {
        this.resources = requireNonNull(resources, "resources is null");
        this.fileWriter = requireNonNull(fileWriter, "fileWriter is null");
    }

    void write(TaskId taskId, CiAutofixRun run)
            throws IOException
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(run, "run is null");
        Path target = resources.require(taskId).runtimeDirectory().resolve(FILE_NAME);
        fileWriter.replace(target, render(run));
    }

    CiMemoryView read(TaskId taskId, Optional<String> requestedQuery)
            throws IOException
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(requestedQuery, "requestedQuery is null");
        Path memory = resources.require(taskId).runtimeDirectory().resolve(FILE_NAME);
        ReadFile file = readBounded(memory);
        List<String> sections = sections(file.markdown());
        String query = boundedQuery(requestedQuery);
        List<String> matches = sections.stream()
                .filter(section -> query.isEmpty()
                        || section.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .collect(toImmutableList());
        if (matches.isEmpty()) {
            return query.isEmpty() ? unavailable() : new CiMemoryView(NO_MATCH, 0, file.truncated());
        }
        List<String> selected = matches.stream().limit(MAX_READ_SECTIONS).collect(toImmutableList());
        String markdown = String.join("\n\n", selected);
        boolean truncated = file.truncated() || matches.size() > selected.size()
                || utf8Length(markdown) > MAX_RESULT_BYTES;
        return new CiMemoryView(boundedUtf8(markdown, MAX_RESULT_BYTES), selected.size(), truncated);
    }

    static CiMemoryView unavailable()
    {
        return new CiMemoryView(UNAVAILABLE, 0, false);
    }

    private static String render(CiAutofixRun run)
    {
        Map<String, List<MemoryAttempt>> grouped = new LinkedHashMap<>();
        run.attempts().stream()
                .sorted(Comparator.comparing(CiAutofixAttempt::updatedAt).reversed())
                .forEach(attempt -> attempt.failures().forEach(failure -> grouped
                        .computeIfAbsent(failure.signature(), ignored -> new ArrayList<>())
                        .add(new MemoryAttempt(attempt, failure))));

        StringBuilder memory = new StringBuilder("# CI Autofix memory\n\n");
        grouped.entrySet().stream().limit(MAX_SIGNATURES).forEach(entry -> {
            memory.append(FAILURE_PREFIX).append(inline(entry.getKey())).append('\n');
            entry.getValue().stream().limit(MAX_ATTEMPTS_PER_SIGNATURE)
                    .forEach(value -> appendAttempt(memory, value));
            memory.append('\n');
        });
        return boundedUtf8(memory.toString(), MAX_FILE_BYTES);
    }

    private static void appendAttempt(StringBuilder memory, MemoryAttempt value)
    {
        CiAutofixAttempt attempt = value.attempt();
        FailureEvidence failure = value.failure();
        memory.append("\n### Attempt ").append(attempt.number())
                .append(" at ").append(attempt.updatedAt()).append('\n')
                .append("- Workflow run: ").append(failure.workflowRunId())
                .append(" (attempt ").append(failure.workflowAttempt()).append(")\n")
                .append("- Job: ").append(failure.jobId())
                .append(" (attempt ").append(failure.jobAttempt()).append(")\n")
                .append("- Input SHA: `").append(attempt.inputSha()).append("`\n")
                .append("- Pushed SHA: ").append(attempt.pushedSha().map(sha -> "`" + sha + "`").orElse("not pushed"))
                .append('\n')
                .append("- Observed CI result: ").append(attempt.observedCiResult()).append('\n')
                .append("- Changed paths: ");
        List<String> paths = attempt.gitResult().map(CiAutofixAttempt.GitResult::changedPaths).orElse(ImmutableList.of());
        memory.append(paths.isEmpty() ? "none recorded" : paths.stream().map(CiMemoryFile::inline).collect(joining(", ")))
                .append("\n- Failure summary:\n")
                .append(quote(failure.summary()));
        attempt.untrustedAgentNote().ifPresent(note -> memory
                .append("\n### Untrusted agent note\n")
                .append(quote(note)));
        memory.append('\n');
    }

    private static void replaceAtomically(Path target, String markdown)
            throws IOException
    {
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "ci-memory-", ".tmp");
        try {
            Files.writeString(temporary, markdown, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic CI memory replacement is unavailable", e);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ReadFile readBounded(Path path)
            throws IOException
    {
        if (!Files.isRegularFile(path)) {
            return new ReadFile("", false);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            boolean truncated = bytes.length > MAX_FILE_BYTES;
            int length = Math.min(bytes.length, MAX_FILE_BYTES);
            return new ReadFile(boundedUtf8(new String(bytes, 0, length, StandardCharsets.UTF_8), MAX_FILE_BYTES), truncated);
        }
    }

    private static List<String> sections(String markdown)
    {
        return SECTION_BOUNDARY.splitAsStream(markdown)
                .filter(section -> section.startsWith(FAILURE_PREFIX))
                .map(String::strip)
                .collect(toImmutableList());
    }

    private static String boundedQuery(Optional<String> query)
    {
        String value = query.orElse("").trim();
        return value.length() <= MAX_QUERY_CHARACTERS ? value : value.substring(0, MAX_QUERY_CHARACTERS);
    }

    private static String inline(String value)
    {
        return value.replace('`', '\'').replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String quote(String value)
    {
        return value.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(line -> "> " + line.replace("\u0000", ""))
                .collect(joining("\n"));
    }

    private static int utf8Length(String value)
    {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String boundedUtf8(String value, int maximumBytes)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) {
            return value;
        }
        int end = maximumBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private record MemoryAttempt(CiAutofixAttempt attempt, FailureEvidence failure) {}

    private record ReadFile(String markdown, boolean truncated) {}

    @FunctionalInterface
    interface MemoryFileWriter
    {
        void replace(Path target, String markdown)
                throws IOException;
    }
}
