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
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskContextDraftService
{
    private static final String WORKSPACE = "workspace";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "e".repeat(40);
    private static final RepoRef FORK = RepoRef.of("author", "repo");

    private final TaskId id = TaskId.random();
    private final TaskWorktree tree = new TaskWorktree(id, WORKSPACE, new RepositoryIdentity("author", "repo"),
            Path.of("/tmp/bytequay-task-test", id.toString()), "bytequay/task/" + id, HEAD);
    private final AgentTask task = AgentTask.created(id, true, "Task", TitleSource.USER, AgentLaneId.CODEX_CLI, tree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ContextBaselineReader reader = mock(ContextBaselineReader.class);
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final ContextSourceChecker checker = mock(ContextSourceChecker.class);
    private final TaskContextDraftService service = new TaskContextDraftService(worktrees, workspaces, reader, notebooks, checker);

    @BeforeEach
    void taskCheckout()
    {
        when(worktrees.inspect(tree)).thenReturn(new WorktreeSnapshot(tree, HEAD, false, false, false));
        when(workspaces.requireLocalRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE,
                Path.of("/tmp/selected"), 2, FORK, OptionalLong.of(1), RepoRef.of("upstream", "repo"), "upstream", "origin", "main", ImmutableList.of()));
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(RepositoryNotebook.empty());
        when(reader.read(any(), eq(2L), eq(HEAD), eq("README.md"), eq(1), eq(Optional.of(2)))).thenAnswer(invocation -> {
            WorkspaceRepositoryContext capturedFrom = invocation.getArgument(0);
            assertThat(capturedFrom.repository()).isEqualTo(tree.canonicalWorktree());
            assertThat(capturedFrom.monitoredRepository()).isEqualTo(FORK);
            return new ContextBaselineReader.Read(new ContextReference.RepositoryFile(2, FORK, "README.md", HEAD),
                    "b".repeat(40), Instant.EPOCH, 1, 2, 3, "1  Verified source\n", false);
        });
        when(checker.base(WORKSPACE, HEAD)).thenReturn(Optional.of(BASE));
    }

    @Test
    void capturedTaskHeadUsesPhysicalForkIdentityAndFrozenNotebookRevision()
    {
        TaskContextDraftService.Prepared prepared = service.prepare(task, proposal(Optional.empty()), "task:" + "c".repeat(32) + ":" + "d".repeat(32));
        assertThat(prepared.expectedRevision()).isZero();
        assertThat(prepared.note().evidence()).singleElement().satisfies(source -> {
            assertThat(source.capture()).isEqualTo(ContextSourceEvidence.Capture.BYTEQUAY_READ);
            assertThat(source.excerpt()).isEqualTo("1  Verified source\n");
            assertThat(source.reference()).isEqualTo(new ContextReference.RepositoryFile(2, FORK, "README.md", HEAD));
        });
        verify(notebooks).getNotebook(WORKSPACE);
        verify(checker).requireAtBase(eq(WORKSPACE), eq(BASE), any());
    }

    @Test
    void notesCiteOnlyMonitoredBranchCodeAndNeverUpdateNewerNotes()
    {
        String operation = "task:" + "c".repeat(32) + ":" + "d".repeat(32);
        when(checker.base(WORKSPACE, HEAD)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepare(task, proposal(Optional.empty()), operation)).hasMessageContaining("monitored branch is not available");

        when(checker.base(WORKSPACE, HEAD)).thenReturn(Optional.of(BASE));
        doThrow(new IllegalArgumentException("The monitored branch at base does not have the cited lines")).when(checker).requireAtBase(eq(WORKSPACE), eq(BASE), any());
        assertThatThrownBy(() -> service.prepare(task, proposal(Optional.empty()), operation)).hasMessageContaining("does not have the cited lines");

        doNothing().when(checker).requireAtBase(eq(WORKSPACE), eq(BASE), any());
        when(checker.newerThan(eq(WORKSPACE), eq(BASE), any())).thenReturn(true);
        assertThat(service.prepare(task, proposal(Optional.empty()), operation).note().entryId()).isEmpty();
        assertThatThrownBy(() -> service.prepare(task, proposal(Optional.of("lesson:project:checkpoint")), operation))
                .hasMessageContaining("newer than this task's base");
    }

    private static TaskContextDraftService.Proposal proposal(Optional<String> entryId)
    {
        return new TaskContextDraftService.Proposal(ContextEntryKind.MEMORY, "project", entryId, entryId.map(ignored -> 3L), "Title", "Summary",
                "Agent-proposed body", ImmutableList.of(new TaskContextDraftService.Source("README.md", 1, Optional.of(2))));
    }
}
