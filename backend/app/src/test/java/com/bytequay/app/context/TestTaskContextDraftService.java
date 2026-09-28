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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskContextDraftService
{
    @Test
    void capturedTaskHeadUsesPhysicalForkIdentityAndFrozenNotebookRevision()
    {
        String workspaceId = "workspace";
        String head = "a".repeat(40);
        RepoRef fork = RepoRef.of("author", "repo");
        RepoRef upstream = RepoRef.of("upstream", "repo");
        RepositoryIdentity physical = new RepositoryIdentity("author", "repo");
        TaskId id = TaskId.random();
        TaskWorktree tree = new TaskWorktree(id, workspaceId, physical, Path.of("/tmp/bytequay-task-test", id.toString()),
                "bytequay/task/" + id, head);
        AgentTask task = AgentTask.created(id, true, "Task", TitleSource.USER, AgentLaneId.CODEX_CLI, tree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
        WorktreeService worktrees = mock(WorktreeService.class);
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        ContextBaselineReader reader = mock(ContextBaselineReader.class);
        RepositoryContextService notebooks = mock(RepositoryContextService.class);
        when(worktrees.inspect(tree)).thenReturn(new WorktreeSnapshot(tree, head, false, false, false));
        when(workspaces.requireLocalRepository(workspaceId)).thenReturn(new WorkspaceRepositoryContext(workspaceId,
                Path.of("/tmp/selected"), 2, fork, OptionalLong.of(1), upstream, "upstream", "origin", "main", ImmutableList.of()));
        when(notebooks.getNotebook(workspaceId)).thenReturn(RepositoryNotebook.empty());
        when(reader.read(any(), eq(2L), eq(head), eq("README.md"), eq(1), eq(Optional.of(2)))).thenAnswer(invocation -> {
            WorkspaceRepositoryContext capturedFrom = invocation.getArgument(0);
            assertThat(capturedFrom.repository()).isEqualTo(tree.canonicalWorktree());
            assertThat(capturedFrom.monitoredRepository()).isEqualTo(fork);
            return new ContextBaselineReader.Read(new ContextReference.RepositoryFile(2, fork, "README.md", head),
                    "b".repeat(40), Instant.EPOCH, 1, 2, 3, "1  Verified source\n", false);
        });
        TaskContextDraftService service = new TaskContextDraftService(worktrees, workspaces, reader, notebooks);
        TaskContextDraftService.Prepared prepared = service.prepare(task, new TaskContextDraftService.Proposal(
                ContextEntryKind.MEMORY, "project", Optional.empty(), Optional.empty(), "Title", "Summary", "Agent-proposed body",
                ImmutableList.of(new TaskContextDraftService.Source("README.md", 1, Optional.of(2)))),
                "draft:task:" + "c".repeat(32) + ":" + "d".repeat(32));
        assertThat(prepared.expectedRevision()).isZero();
        assertThat(prepared.proposal().evidence()).singleElement().satisfies(source -> {
            assertThat(source.capture()).isEqualTo(ContextSourceEvidence.Capture.BYTEQUAY_READ);
            assertThat(source.excerpt()).isEqualTo("1  Verified source\n");
            assertThat(source.reference()).isEqualTo(new ContextReference.RepositoryFile(2, fork, "README.md", head));
        });
        verify(notebooks).getNotebook(workspaceId);
    }
}
