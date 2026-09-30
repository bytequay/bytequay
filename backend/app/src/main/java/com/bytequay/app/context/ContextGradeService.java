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
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    /** An exact read of a contested note carries at most this many Wrong and Holds grades from both sides. */
    static final int READ_SIDES = 6;
    /** Review runs retain exact note reads under this operation-key prefix. */
    public static final String NOTE_READ_PREFIX = "context-note:";
    private static final int REPORT_PAGE = 1000;

    private final ContextGradeStore grades;
    private final RepositoryContextService notebooks;
    private final DevelopmentContextReceiptStore receipts;
    private final AgentTaskStore tasks;
    private final AgentReviewStore reviews;
    private final ContextSourceChecker checker;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ContextGradeService(ContextGradeStore grades, RepositoryContextService notebooks, DevelopmentContextReceiptStore receipts, AgentTaskStore tasks,
            AgentReviewStore reviews, ContextSourceChecker checker, ObjectMapper mapper, Clock clock)
    {
        this.grades = requireNonNull(grades, "grades is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.receipts = requireNonNull(receipts, "receipts is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.checker = requireNonNull(checker, "checker is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Checks a Wrong or Holds against the version it quotes: that version must be current and enabled and contain the quote,
     * and a Holds must answer an open report on it.
     */
    public ContextEntry requireStatement(String workspaceId, RepositoryNotebook notebook, String entryId, long version, ContextGrade.Grade grade, String quote)
    {
        checkArgument(grade == ContextGrade.Grade.WRONG || grade == ContextGrade.Grade.HOLDS, "Only Wrong and Holds quote a statement");
        ContextEntry entry = notebook.entries().stream().filter(value -> value.id().equals(entryId) && value.version() == version && value.enabled())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The note changed since you read it; read the current version before disputing a statement"));
        checkArgument(ContextLimits.bytes(quote) <= ContextGrade.MAX_QUOTE_BYTES, "quote exceeds %s UTF-8 bytes", ContextGrade.MAX_QUOTE_BYTES);
        checkArgument(text(entry).contains(quote), "The quote is not in that note version; quote its statement exactly");
        if (grade == ContextGrade.Grade.HOLDS) {
            checkArgument(reportsFor(workspaceId, entry).stream()
                            .anyMatch(report -> report.quote().map(reported -> reported.contains(quote) || quote.contains(reported)).orElse(true)),
                    "HOLDS answers an open report on this version; quote the reported statement");
        }
        return entry;
    }

    /** Saves an agent's grade. A Holds answers an open report, so agents now disagree and the note becomes contested. */
    public void save(ContextGrade grade)
    {
        grades.save(grade);
        if (grade.grade() == ContextGrade.Grade.HOLDS) {
            notebooks.contest(grade.workspaceId(), grade.note().id(), grade.note().version(), grade.quote().orElseThrow());
        }
    }

    /**
     * Whether a correction of {@code graded}, the current version, disputes a settled statement while none of that version's
     * cited repository lines changed at the corrector's {@code base}. Settled are a correction's or an agent settlement's
     * statement that the text still holds, and every statement of a version the user kept or restored while it is current.
     * Git reads only; call outside the notebook lock.
     */
    public boolean disputesSettled(String workspaceId, RepositoryNotebook notebook, ContextEntry graded, String quote, String base)
    {
        String text = text(graded);
        boolean settled = notebooks.getDisputes(workspaceId).settlement(graded.id())
                .map(settlement -> settlement.by() == ContextDisputes.By.USER
                        ? settlement.version() == graded.version()
                        : text.contains(settlement.statement()) && overlaps(settlement.statement(), quote))
                .orElse(false)
                // ponytail: scans the workspace's newest 1000 Wrong grades; add a per-note query if corrections outgrow it.
                || grades.listGrades(workspaceId, ImmutableSet.of(ContextGrade.Grade.WRONG), REPORT_PAGE).stream()
                        .filter(grade -> grade.note().id().equals(graded.id()))
                        .flatMap(grade -> grade.correction().stream())
                        .anyMatch(correction -> text.contains(correction.statement()) && overlaps(correction.statement(), quote));
        if (!settled) {
            return false;
        }
        Set<String> cited = ImmutableSet.copyOf(graded.sourceIds());
        Map<String, ContextSourceEvidence> sources = notebook.sourceEvidence().stream()
                .filter(source -> cited.contains(source.id()) && source.reference() instanceof ContextReference.RepositoryFile)
                .collect(toImmutableMap(ContextSourceEvidence::id, source -> source));
        // Lines that cannot be compared count as changed: the code may have moved on, so the correction stays an ordinary fix.
        return !sources.isEmpty() && checker.check(workspaceId, Optional.of(base), sources).sources().stream()
                .allMatch(source -> source.status() == ContextSourceCheck.Status.UNCHANGED);
    }

    private static boolean overlaps(String statement, String quote)
    {
        return statement.contains(quote) || quote.contains(statement);
    }

    /** What a reader of this exact version sees while agents contest the note; empty unless it is the current, enabled version. */
    public Optional<Dispute> disputeFor(String workspaceId, String entryId, long version)
    {
        ContextDisputes disputes = notebooks.getDisputes(workspaceId);
        Optional<ContextDisputes.Contest> contest = disputes.contest(entryId);
        if (contest.isEmpty()) {
            return Optional.empty();
        }
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        return notebook.entries().stream().filter(entry -> entry.id().equals(entryId) && entry.version() == version && entry.enabled()).findFirst().map(entry -> {
            Optional<ContextEntry> previous = previous(notebook, entryId);
            List<Side> sides = graded(workspaceId, notebook, entry, contest).stream()
                    .filter(grade -> grade.grade() == ContextGrade.Grade.WRONG || grade.grade() == ContextGrade.Grade.HOLDS)
                    .limit(READ_SIDES).map(Side::from).collect(toImmutableList());
            return new Dispute(contest.orElseThrow().statement(), contest.orElseThrow().version(), previous.map(ContextEntry::version),
                    previous.map(ContextGradeService::text), sides, disputes.settlement(entryId));
        });
    }

    /** Grades of the current version and, for a contested note, of its previous and first disputed versions, newest first. */
    private List<ContextGrade> graded(String workspaceId, RepositoryNotebook notebook, ContextEntry entry, Optional<ContextDisputes.Contest> contest)
    {
        Stream<Long> versions = contest.isEmpty()
                ? Stream.of(entry.version())
                : Stream.concat(Stream.of(entry.version(), contest.orElseThrow().version()), previous(notebook, entry.id()).map(ContextEntry::version).stream());
        return versions.distinct().flatMap(version -> grades.listVersion(workspaceId, entry.id(), version, REPORT_PAGE).stream())
                .sorted(Comparator.comparing(ContextGrade::gradedAt).reversed()).collect(toImmutableList());
    }

    private static Optional<ContextEntry> previous(RepositoryNotebook notebook, String entryId)
    {
        return notebook.replaced().stream().filter(entry -> entry.id().equals(entryId)).findFirst();
    }

    /**
     * Saves a review's grade of an exact version its run received or opened; the caller captured the cited lines at the
     * run's monitored baseline. Reviews cannot change notes, so a Wrong stays an open report.
     */
    public ContextGrade gradeByReview(String workspaceId, String taskId, String runId, String baseline, String entryId, long version, ContextGrade.Grade grade,
            String reason, Optional<String> quote, List<ContextGrade.Citation> citations)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        ContextEntry graded;
        if (quote.isPresent()) {
            graded = requireStatement(workspaceId, notebook, entryId, version, grade, quote.orElseThrow());
            Set<String> cited = ImmutableSet.copyOf(graded.sourceIds());
            checkArgument(!checker.newerThan(workspaceId, baseline, notebook.sourceEvidence().stream().filter(source -> cited.contains(source.id())).toList()),
                    "This note was saved from code newer than the review's baseline; don't dispute it from older code");
        }
        else {
            graded = Stream.concat(notebook.entries().stream(), notebook.replaced().stream())
                    .filter(value -> value.id().equals(entryId) && value.version() == version).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That note version is no longer kept; grade the version you read now"));
        }
        ContextGrade saved = new ContextGrade(workspaceId, taskId, Optional.of(runId), ContextUsageEvent.TaskKind.PR_REVIEW, ContextGrade.Grader.AGENT, grade,
                ContextUsageEvent.Note.from(graded), reason, quote, citations, Optional.empty(), Instant.now(clock));
        save(saved);
        return saved;
    }

    static String text(ContextEntry entry)
    {
        StringBuilder text = new StringBuilder(entry.title()).append('\n').append(entry.summary()).append('\n').append(entry.body());
        entry.claims().forEach(claim -> text.append('\n').append(claim.text()));
        return text.toString();
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

    /** Whether a review run received this exact note version in its captured packet or opened it with an exact read. */
    public boolean receivedByReview(ReviewRun run, String entryId, long version)
    {
        return run.context().filter(ReviewContextSnapshot::enabled).map(snapshot -> snapshot.selectedEntries().stream()
                        .anyMatch(entry -> entry.entryId().equals(entryId) && entry.entryVersion() == version && entry.inclusion() == ContextSelectedEntry.Inclusion.INCLUDED))
                .orElse(false)
                || run.operations().entrySet().stream().filter(operation -> operation.getKey().startsWith(NOTE_READ_PREFIX)).anyMatch(operation -> {
                    JsonNode result = tree(operation.getValue().result());
                    return result.path("status").asText().equals("AVAILABLE") && result.path("entryId").asText().equals(entryId)
                            && result.path("entryVersion").asLong() == version;
                });
    }

    /**
     * Enabled notes whose current version has open reports or that agents contest, with how the note's last dispute was
     * settled and the grades of the versions in question, newest first.
     */
    public List<ReportedNote> reportedNotes(String workspaceId)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        ContextDisputes disputes = notebooks.getDisputes(workspaceId);
        return Stream.concat(openReports(workspaceId, notebook).stream().map(report -> report.note().id()), disputes.contested().keySet().stream()).distinct()
                .flatMap(id -> notebook.entries().stream().filter(entry -> entry.id().equals(id) && entry.enabled()).findFirst().stream())
                .map(entry -> new ReportedNote(entry.id(), entry.version(), entry.title(), entry.origin() == ContextEntryOrigin.USER,
                        graded(workspaceId, notebook, entry, disputes.contest(entry.id())), disputes.contest(entry.id()), disputes.settlement(entry.id())))
                .collect(toImmutableList());
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

    /** Open reports on this exact version, or none when it is no longer the current version of an enabled note. */
    public List<ContextGrade> reportsFor(String workspaceId, String entryId, long version)
    {
        return notebooks.getNotebook(workspaceId).entries().stream().filter(entry -> entry.id().equals(entryId) && entry.version() == version).findFirst()
                .map(entry -> reportsFor(workspaceId, entry)).orElse(ImmutableList.of());
    }

    /** The user reports a note version one of the workspace's tasks or reviews received or opened as wrong. */
    public ContextGrade report(String workspaceId, String taskId, String entryId, long version, String reason)
    {
        Optional<AgentReview> review = reviews.findReviewByTask(taskId);
        Optional<String> runId;
        if (review.isPresent()) {
            checkArgument(review.orElseThrow().workspaceId().equals(workspaceId), "The review belongs to another workspace");
            runId = reviews.listRuns(review.orElseThrow().id()).stream().filter(run -> receivedByReview(run, entryId, version)).map(ReviewRun::id)
                    .reduce((earlier, later) -> later);
            checkArgument(runId.isPresent(), "Report only a note version this review received or opened");
        }
        else {
            AgentTask task = tasks.find(TaskId.valueOf(taskId)).orElseThrow(() -> new ContextNotFoundException("Task not found"));
            checkArgument(task.workspaceId().filter(workspaceId::equals).isPresent(), "The task belongs to another workspace");
            checkArgument(received(taskId, entryId, version), "Report only a note version this task received or opened");
            runId = Optional.empty();
        }
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        ContextEntry entry = Stream.concat(notebook.entries().stream(), notebook.replaced().stream())
                .filter(value -> value.id().equals(entryId) && value.version() == version).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That note version is no longer kept; open the note to report its current version"));
        ContextGrade grade = new ContextGrade(workspaceId, taskId, runId,
                runId.isPresent() ? ContextUsageEvent.TaskKind.PR_REVIEW : ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.USER,
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

    /**
     * A note with open reports on its current version or that agents contest. Grades cover the current version and, while
     * contested, the previous and first disputed versions.
     */
    public record ReportedNote(String entryId, long entryVersion, String title, boolean userWritten, List<ContextGrade> grades,
            Optional<ContextDisputes.Contest> contested, Optional<ContextDisputes.Settlement> settlement)
    {
        public ReportedNote
        {
            grades = ImmutableList.copyOf(grades);
            requireNonNull(contested, "contested is null");
            requireNonNull(settlement, "settlement is null");
        }
    }

    /**
     * Agents disagree about {@code statement}, first disputed on version {@code since}: the previous text, each side's Wrong
     * or Holds, and how an earlier dispute of the note was settled.
     */
    public record Dispute(String statement, long since, Optional<Long> previousVersion, Optional<String> previousText, List<Side> sides,
            Optional<ContextDisputes.Settlement> earlierSettlement)
    {
        public Dispute
        {
            sides = ImmutableList.copyOf(sides);
        }
    }

    /** One side of a dispute: who said what about which version, citing which lines, and the statement a correction wrote. */
    public record Side(String grader, String grade, long version, String reason, Optional<String> quote, List<String> citedLines,
            Optional<String> replacement, Instant gradedAt)
    {
        public Side
        {
            citedLines = ImmutableList.copyOf(citedLines);
        }

        static Side from(ContextGrade grade)
        {
            return new Side(grade.grader().name(), grade.grade().name(), grade.note().version(), grade.reason(), grade.quote(),
                    grade.citations().stream().map(citation -> citation.path() + ":" + citation.startLine() + "-" + citation.endLine()).collect(toImmutableList()),
                    grade.correction().map(ContextGrade.Correction::statement), grade.gradedAt());
        }
    }
}
