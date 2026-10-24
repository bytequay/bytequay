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

import com.bytequay.app.utils.ContextText;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.google.common.base.Ascii;
import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

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

@Service
public class CiMemoryService
{
    private static final String FAILURE_PREFIX = "## Failure ";
    private static final String UNAVAILABLE = "CI memory is unavailable. Continue with the current CI logs and repository.";
    private static final String NO_MATCH = "No CI memory matched the query. Continue with the current CI logs and repository.";
    private static final int MAX_SIGNATURES = 50;
    private static final int MAX_ATTEMPTS_PER_SIGNATURE = 5;
    private static final int MAX_QUERY_CHARACTERS = 200;
    private static final int MAX_READ_SECTIONS = 10;
    private static final int MAX_MEMORY_BYTES = 256 * 1024;
    private static final int MAX_RESULT_BYTES = 32 * 1024;
    private static final Pattern SECTION_BOUNDARY = Pattern.compile("(?m)(?=^## Failure )");

    private final CiAutofixStore store;

    CiMemoryService(CiAutofixStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Reads bounded memory owned by the authenticated task, rendered from its durable CI Autofix facts.
     *
     * @param command injected task identity and optional substring query
     * @return recent or matching memory sections; unavailable memory is a normal result
     */
    public CiMemoryView read(ReadCiMemoryCommand command)
    {
        requireNonNull(command, "command is null");
        String taskId = command.taskId().toString();
        Optional<CiAutofixRun> run = store.findByAgentTaskId(taskId)
                .filter(found -> found.agentTaskId().filter(taskId::equals).isPresent());
        if (run.isEmpty()) {
            return unavailable();
        }
        List<String> sections = sections(render(run.orElseThrow()));
        String query = Ascii.truncate(command.query().orElse("").trim(), MAX_QUERY_CHARACTERS, "");
        List<String> matches = sections.stream()
                .filter(section -> query.isEmpty()
                        || section.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .collect(toImmutableList());
        if (matches.isEmpty()) {
            return query.isEmpty() ? unavailable() : new CiMemoryView(NO_MATCH, 0, false);
        }
        List<String> selected = matches.stream().limit(MAX_READ_SECTIONS).collect(toImmutableList());
        String markdown = String.join("\n\n", selected);
        boolean truncated = matches.size() > selected.size() || Utf8.encodedLength(markdown) > MAX_RESULT_BYTES;
        return new CiMemoryView(ContextText.prefix(markdown, MAX_RESULT_BYTES), selected.size(), truncated);
    }

    private static CiMemoryView unavailable()
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
        return ContextText.prefix(memory.toString(), MAX_MEMORY_BYTES);
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
        memory.append(paths.isEmpty() ? "none recorded" : paths.stream().map(CiMemoryService::inline).collect(joining(", ")))
                .append("\n- Failure summary:\n")
                .append(quote(failure.summary()));
        attempt.untrustedAgentNote().ifPresent(note -> memory
                .append("\n### Untrusted agent note\n")
                .append(quote(note)));
        memory.append('\n');
    }

    private static List<String> sections(String markdown)
    {
        return SECTION_BOUNDARY.splitAsStream(markdown)
                .filter(section -> section.startsWith(FAILURE_PREFIX))
                .map(String::strip)
                .collect(toImmutableList());
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

    private record MemoryAttempt(CiAutofixAttempt attempt, FailureEvidence failure) {}
}
