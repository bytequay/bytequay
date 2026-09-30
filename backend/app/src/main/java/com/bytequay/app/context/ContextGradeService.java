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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextGradeStore;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;

/** Reads grades against the current notebook: which reports are open and what a reader of a note version must re-check. */
@Service
public class ContextGradeService
{
    /** An exact read carries at most this many open reports. */
    static final int READ_REPORTS = 3;
    private static final int REPORT_PAGE = 1000;

    private final ContextGradeStore grades;
    private final RepositoryContextService notebooks;
    private final DevelopmentContextReceiptStore receipts;
    private final AgentTaskStore tasks;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ContextGradeService(ContextGradeStore grades, RepositoryContextService notebooks, DevelopmentContextReceiptStore receipts, AgentTaskStore tasks,
            ObjectMapper mapper, Clock clock)
    {
        this.grades = requireNonNull(grades, "grades is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.receipts = requireNonNull(receipts, "receipts is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Whether a development task received this exact note version in a saved offer or opened it with READ. */
    public boolean received(String taskId, String entryId, long version)
    {
        return receipts.listTask(taskId, 101).stream().anyMatch(receipt -> {
            JsonNode payload = tree(receipt.payloadJson());
            return switch (receipt.kind()) {
                case OFFER -> payload.path("entries").isArray() && ImmutableList.copyOf(payload.path("entries")).stream()
                        .anyMatch(entry -> entry.path("included").asBoolean() && entry.path("entryId").asText().equals(entryId)
                                && entry.path("entryVersion").asLong() == version);
                case READ -> payload.path("status").asText().equals("AVAILABLE") && payload.path("entryId").asText().equals(entryId)
                        && payload.path("entryVersion").asLong() == version;
                default -> false;
            };
        });
    }

    /** Notes whose current version has open reports, with every grade of that version, newest first. */
    public List<ReportedNote> reportedNotes(String workspaceId)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        return openReports(workspaceId, notebook).stream().map(report -> report.note().id()).distinct().map(id -> {
            ContextEntry entry = notebook.entries().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            return new ReportedNote(entry.id(), entry.version(), entry.title(), entry.origin() == ContextEntryOrigin.USER,
                    grades.listVersion(workspaceId, entry.id(), entry.version(), REPORT_PAGE));
        }).collect(toImmutableList());
    }

    /** Wrong reports on the current version of an enabled note, newest first; any newer version closes a report. */
    public List<ContextGrade> openReports(String workspaceId, RepositoryNotebook notebook)
    {
        Map<String, Long> current = notebook.entries().stream().filter(ContextEntry::enabled).collect(toImmutableMap(ContextEntry::id, ContextEntry::version));
        return grades.listGrades(workspaceId, ImmutableSet.of(ContextGrade.Grade.WRONG), REPORT_PAGE).stream()
                .filter(grade -> Objects.equals(current.get(grade.note().id()), grade.note().version())).collect(toImmutableList());
    }

    /** The open reports a reader of this exact version sees, newest first. */
    public List<ContextGrade> reportsFor(String workspaceId, ContextEntry entry)
    {
        if (!entry.enabled()) {
            return ImmutableList.of();
        }
        return grades.listVersion(workspaceId, entry.id(), entry.version(), REPORT_PAGE).stream()
                .filter(grade -> grade.grade() == ContextGrade.Grade.WRONG).limit(READ_REPORTS).collect(toImmutableList());
    }

    /** The user reports a note version one of the workspace's development tasks received or opened as wrong. */
    public ContextGrade report(String workspaceId, String taskId, String entryId, long version, String reason)
    {
        AgentTask task = tasks.find(TaskId.valueOf(taskId)).orElseThrow(() -> new ContextNotFoundException("Task not found"));
        checkArgument(task.workspaceId().filter(workspaceId::equals).isPresent(), "The task belongs to another workspace");
        checkArgument(received(taskId, entryId, version), "Report only a note version this task received or opened");
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        ContextEntry entry = Stream.concat(notebook.entries().stream(), notebook.replaced().stream())
                .filter(value -> value.id().equals(entryId) && value.version() == version).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That note version is no longer kept; open the note to report its current version"));
        ContextGrade grade = new ContextGrade(workspaceId, taskId, Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.USER,
                ContextGrade.Grade.WRONG, ContextUsageEvent.Note.from(entry), reason.strip(), Optional.empty(), ImmutableList.of(), Optional.empty(), Instant.now(clock));
        grades.save(grade);
        return grade;
    }

    /** Distinct grading tasks per note since {@code since}: helped, not relevant and wrong. */
    public Map<String, Counts> counts(String workspaceId, Instant since)
    {
        checkArgument(!since.isAfter(Instant.now(clock)), "The counting window cannot start in the future");
        return grades.count(workspaceId, since).stream().collect(groupingBy(ContextGradeStore.Count::entryId, collectingAndThen(toImmutableList(),
                rows -> new Counts(tasks(rows, ContextGrade.Grade.HELPED), tasks(rows, ContextGrade.Grade.NOT_RELEVANT), tasks(rows, ContextGrade.Grade.WRONG)))));
    }

    private static long tasks(List<ContextGradeStore.Count> rows, ContextGrade.Grade grade)
    {
        return rows.stream().filter(row -> row.grade() == grade).mapToLong(ContextGradeStore.Count::tasks).sum();
    }

    private JsonNode tree(String json)
    {
        try {
            return mapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new ContextStorageException("Saved context receipt could not be read", failure);
        }
    }

    public record Counts(long helped, long notRelevant, long wrong) {}

    /** A note with open reports on its current version and every grade of that version. */
    public record ReportedNote(String entryId, long entryVersion, String title, boolean userWritten, List<ContextGrade> grades)
    {
        public ReportedNote
        {
            grades = ImmutableList.copyOf(grades);
        }
    }
}
