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
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextGradeStore;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestContextGradeService
{
    private static final String WORKSPACE = "workspace";
    private static final String BASE = "b".repeat(40);
    private static final String STATEMENT = "Refresh the snapshot before reading";

    private final ContextGradeStore grades = mock(ContextGradeStore.class);
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final DevelopmentContextReceiptStore receipts = mock(DevelopmentContextReceiptStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ContextSourceChecker checker = mock(ContextSourceChecker.class);
    private final ContextGradeService service = new ContextGradeService(grades, notebooks, receipts, tasks, mock(AgentReviewStore.class), checker,
            new ObjectMapper().registerModule(new Jdk8Module()),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void onlyReportsOnTheCurrentVersionOfAnEnabledNoteAreOpen()
    {
        ContextEntry current = entry("knowledge:project", 5, true);
        ContextEntry disabled = entry("knowledge:project:checkpoints", 3, false);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 6, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(current, disabled), ImmutableList.of(), Optional.empty());
        ContextGrade open = report(current.id(), 5);
        when(grades.listGrades(eq(WORKSPACE), any(), eq(1000))).thenReturn(ImmutableList.of(open, report(current.id(), 4), report(disabled.id(), 3)));

        assertThat(service.openReports(WORKSPACE, notebook)).containsExactly(open);
        assertThat(service.reportsFor(WORKSPACE, disabled)).isEmpty();
    }

    @Test
    void usersReportOnlyVersionsTheirTaskReceived()
    {
        TaskId id = TaskId.random();
        AgentTask task = AgentTask.created(id, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI,
                new TaskWorktree(id, WORKSPACE, new RepositoryIdentity("example", "project"), Path.of("/tmp/bytequay-grade", id.toString()), "bytequay/task/" + id, "a".repeat(40)),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
        when(tasks.find(id)).thenReturn(Optional.of(task));
        when(receipts.listTask(id.toString(), 101)).thenReturn(ImmutableList.of(new DevelopmentContextReceipt(id.toString(), "turn", "read", "context-note:1",
                DevelopmentContextReceipt.Kind.READ, "digest", "{\"status\":\"AVAILABLE\",\"entryId\":\"knowledge:project\",\"entryVersion\":4}", "digest", Instant.EPOCH)));
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(new RepositoryNotebook(1, 6, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(entry("knowledge:project", 5, true)), ImmutableList.of(entry("knowledge:project", 4, true)), ImmutableList.of(), Optional.empty()));

        assertThatThrownBy(() -> service.report(WORKSPACE, id.toString(), "knowledge:project", 5, "Wrong path"))
                .hasMessageContaining("received or opened");
        assertThatThrownBy(() -> service.report("another-workspace", id.toString(), "knowledge:project", 4, "Wrong path"))
                .hasMessageContaining("another workspace");
        ContextGrade saved = service.report(WORKSPACE, id.toString(), "knowledge:project", 4, "  Wrong path  ");

        ArgumentCaptor<ContextGrade> captured = ArgumentCaptor.forClass(ContextGrade.class);
        verify(grades).save(captured.capture());
        assertThat(captured.getValue()).isEqualTo(saved);
        assertThat(saved.grader()).isEqualTo(ContextGrade.Grader.USER);
        assertThat(saved.grade()).isEqualTo(ContextGrade.Grade.WRONG);
        assertThat(saved.note().version()).isEqualTo(4);
        assertThat(saved.reason()).isEqualTo("Wrong path");
    }

    @Test
    void holdsContestsTheNoteItAnswers()
    {
        ContextGrade holds = agentGrade(5, ContextGrade.Grade.HOLDS, Optional.empty(), Instant.EPOCH);
        service.save(holds);
        verify(grades).save(holds);
        verify(notebooks).contest(WORKSPACE, "knowledge:project", 5, STATEMENT);

        service.save(agentGrade(5, ContextGrade.Grade.HELPED, Optional.empty(), Instant.EPOCH));
        verify(notebooks).contest(any(), any(), anyLong(), any());
    }

    @Test
    void aCorrectionDisputesASettledStatementOnlyWhileItsLinesAreUnchanged()
    {
        ContextSourceEvidence source = new ContextSourceEvidence("task-read:1", new ContextReference.RepositoryFile(1, RepoRef.of("example", "project"), "Snapshot.java",
                "c".repeat(40)), "Committed task HEAD", "12  refresh();\n", ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH),
                Optional.of("d".repeat(40)), Optional.of("c".repeat(40)), false);
        ContextEntry current = new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Title", "Summary", STATEMENT, ImmutableList.of(),
                ImmutableList.of(source.id()), ContextEntryOrigin.GENERATED, true, 5);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 6, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(current),
                ImmutableList.of(), ImmutableList.of(source), Optional.empty());
        when(notebooks.getDisputes(WORKSPACE)).thenReturn(ContextDisputes.empty());
        when(grades.listGrades(eq(WORKSPACE), any(), eq(1000))).thenReturn(ImmutableList.of(agentGrade(4, ContextGrade.Grade.WRONG,
                Optional.of(new ContextGrade.Correction(5, STATEMENT)), Instant.EPOCH)));
        when(checker.check(eq(WORKSPACE), eq(Optional.of(BASE)), any())).thenReturn(check(ContextSourceCheck.Status.UNCHANGED));

        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Refresh the snapshot", BASE)).isTrue();
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Summary", BASE)).isFalse();
        when(checker.check(eq(WORKSPACE), eq(Optional.of(BASE)), any())).thenReturn(check(ContextSourceCheck.Status.CHANGED));
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Refresh the snapshot", BASE)).isFalse();

        when(checker.check(eq(WORKSPACE), eq(Optional.of(BASE)), any())).thenReturn(check(ContextSourceCheck.Status.UNCHANGED));
        when(grades.listGrades(eq(WORKSPACE), any(), eq(1000))).thenReturn(ImmutableList.of());
        settled(new ContextDisputes.Settlement(5, ContextDisputes.By.USER, "An earlier statement", Optional.empty()));
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Summary", BASE)).isTrue();
        settled(new ContextDisputes.Settlement(4, ContextDisputes.By.USER, "An earlier statement", Optional.empty()));
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Summary", BASE)).isFalse();
        settled(new ContextDisputes.Settlement(3, ContextDisputes.By.INVESTIGATION, STATEMENT, Optional.of("The readers used different configurations")));
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "before reading", BASE)).isTrue();
        assertThat(service.disputesSettled(WORKSPACE, notebook, current, "Summary", BASE)).isFalse();
    }

    @Test
    void contestedNotesNeedTheUserWithBothVersionsGradesAndTheirEarlierSettlement()
    {
        ContextEntry current = entry("knowledge:project", 6, true);
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(new RepositoryNotebook(1, 7, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(current), ImmutableList.of(entry("knowledge:project", 5, true)), ImmutableList.of(), Optional.empty()));
        ContextDisputes.Contest contest = new ContextDisputes.Contest("Body v5", 5);
        ContextDisputes.Settlement earlier = new ContextDisputes.Settlement(3, ContextDisputes.By.INVESTIGATION, "Body v3",
                Optional.of("The readers used different configurations"));
        when(notebooks.getDisputes(WORKSPACE)).thenReturn(new ContextDisputes(ImmutableMap.of(current.id(), contest), ImmutableMap.of(current.id(), earlier)));
        ContextGrade corrected = agentGrade(5, ContextGrade.Grade.WRONG, Optional.of(new ContextGrade.Correction(6, "Body v6")), Instant.EPOCH);
        ContextGrade helped = agentGrade(6, ContextGrade.Grade.HELPED, Optional.empty(), Instant.EPOCH.plusSeconds(60));
        when(grades.listGrades(eq(WORKSPACE), any(), eq(1000))).thenReturn(ImmutableList.of());
        when(grades.listVersion(WORKSPACE, current.id(), 6, 1000)).thenReturn(ImmutableList.of(helped));
        when(grades.listVersion(WORKSPACE, current.id(), 5, 1000)).thenReturn(ImmutableList.of(corrected));

        assertThat(service.reportedNotes(WORKSPACE)).singleElement().satisfies(note -> {
            assertThat(note.entryVersion()).isEqualTo(6);
            assertThat(note.contested()).contains(contest);
            assertThat(note.settlement()).contains(earlier);
            assertThat(note.grades()).containsExactly(helped, corrected);
        });
        assertThat(service.disputeFor(WORKSPACE, current.id(), 6)).hasValueSatisfying(dispute -> {
            assertThat(dispute.statement()).isEqualTo("Body v5");
            assertThat(dispute.previousVersion()).contains(5L);
            assertThat(dispute.previousText()).hasValueSatisfying(text -> assertThat(text).contains("Body v5"));
            assertThat(dispute.sides()).singleElement().satisfies(side -> {
                assertThat(side.version()).isEqualTo(5);
                assertThat(side.quote()).contains(STATEMENT);
                assertThat(side.replacement()).contains("Body v6");
                assertThat(side.citedLines()).containsExactly("Snapshot.java:12-12");
            });
            assertThat(dispute.earlierSettlement()).contains(earlier);
        });
        assertThat(service.disputeFor(WORKSPACE, current.id(), 5)).isEmpty();
    }

    private void settled(ContextDisputes.Settlement settlement)
    {
        when(notebooks.getDisputes(WORKSPACE)).thenReturn(new ContextDisputes(ImmutableMap.of(), ImmutableMap.of("knowledge:project", settlement)));
    }

    private static ContextSourceCheck check(ContextSourceCheck.Status status)
    {
        return new ContextSourceCheck(Optional.of(BASE), ImmutableList.of(new ContextSourceCheck.Source("task-read:1", "REPOSITORY_FILE", Optional.of("Snapshot.java"),
                Optional.of("12-12"), Optional.empty(), Optional.of("c".repeat(40)), status)), ImmutableList.of());
    }

    private static ContextGrade agentGrade(long version, ContextGrade.Grade grade, Optional<ContextGrade.Correction> correction, Instant at)
    {
        boolean statement = grade == ContextGrade.Grade.WRONG || grade == ContextGrade.Grade.HOLDS;
        return new ContextGrade(WORKSPACE, "task-" + version + grade, Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.AGENT, grade,
                new ContextUsageEvent.Note("knowledge:project", version, ContextEntryKind.KNOWLEDGE, "project", "Title"), "Checked the code",
                statement ? Optional.of(STATEMENT) : Optional.empty(),
                statement ? ImmutableList.of(new ContextGrade.Citation("Snapshot.java", 12, 12, BASE, "12  refresh();\n")) : ImmutableList.of(), correction, at);
    }

    private static ContextGrade report(String entryId, long version)
    {
        return new ContextGrade(WORKSPACE, "task-" + entryId + version, Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.USER,
                ContextGrade.Grade.WRONG, new ContextUsageEvent.Note(entryId, version, ContextEntryKind.KNOWLEDGE, "project", "Title"), "", Optional.empty(),
                ImmutableList.of(), Optional.empty(), Instant.EPOCH);
    }

    private static ContextEntry entry(String id, long version, boolean enabled)
    {
        return new ContextEntry(id, ContextEntryKind.KNOWLEDGE, "project", "Title", "Summary", "Body v" + version, ImmutableList.of(), ImmutableList.of(),
                ContextEntryOrigin.USER, enabled, version);
    }
}
