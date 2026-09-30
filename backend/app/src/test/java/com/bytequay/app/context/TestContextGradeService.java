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
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestContextGradeService
{
    private static final String WORKSPACE = "workspace";

    private final ContextGradeStore grades = mock(ContextGradeStore.class);
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final DevelopmentContextReceiptStore receipts = mock(DevelopmentContextReceiptStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ContextGradeService service = new ContextGradeService(grades, notebooks, receipts, tasks, new ObjectMapper().registerModule(new Jdk8Module()),
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
