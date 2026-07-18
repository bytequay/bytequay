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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCodeGraphToolHandlers
{
    @Test
    void aCheckoutSelectionErrorDoesNotUnlockNativeSearch()
    {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-" + suffix;
        String taskId = "task-" + suffix;
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTaskById(taskId)).thenReturn(Optional.empty());
        CodeGraphToolHandlers handlers = new CodeGraphToolHandlers(
                mock(CodeGraphUpdateCoordinator.class), tasks, mock(ThreadStore.class),
                mock(WorkspaceService.class), mock(WatchedRepoStore.class),
                mock(WorktreeService.class));
        CodeGraphFirstRuntime.prepare(new ProcessBuilder("/usr/bin/true"), threadId, taskId);

        ToolOutcome outcome = handlers.codegraphExplore(
                new CodeGraphToolHandlers.CodeGraphExploreArgs("locate the feature", null),
                new ToolCall(threadId, null, AgentRole.TASK, taskId, null));

        assertThat(outcome).isEqualTo(ToolOutcome.Completed.error(
                "no usable local checkout is bound to task " + taskId));
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isTrue();
    }

    @Test
    void anInvalidEmptyQueryDoesNotCountAsAGraphAttempt()
    {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-" + suffix;
        String taskId = "task-" + suffix;
        CodeGraphToolHandlers handlers = new CodeGraphToolHandlers(
                mock(CodeGraphUpdateCoordinator.class), mock(TaskStore.class), mock(ThreadStore.class),
                mock(WorkspaceService.class), mock(WatchedRepoStore.class),
                mock(WorktreeService.class));
        CodeGraphFirstRuntime.prepare(new ProcessBuilder("/usr/bin/true"), threadId, taskId);

        handlers.codegraphExplore(
                new CodeGraphToolHandlers.CodeGraphExploreArgs(" ", null),
                new ToolCall(threadId, null, AgentRole.TASK, taskId, null));

        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isTrue();
    }

    @Test
    void symbolModeUsesTheIndexedQueryAndRecordsSuccess(@TempDir Path checkout)
            throws Exception
    {
        Files.createDirectory(checkout.resolve(".git"));
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-" + suffix;
        String taskId = "task-" + suffix;
        Task task = mock(Task.class);
        when(task.worktreePath()).thenReturn(checkout.toString());
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTaskById(taskId)).thenReturn(Optional.of(task));
        CodeGraphUpdateCoordinator codeGraph = mock(CodeGraphUpdateCoordinator.class);
        when(codeGraph.query(checkout.toAbsolutePath().normalize(), "AuthToken"))
                .thenReturn("symbol result");
        CodeGraphToolHandlers handlers = new CodeGraphToolHandlers(
                codeGraph, tasks, mock(ThreadStore.class), mock(WorkspaceService.class),
                mock(WatchedRepoStore.class), mock(WorktreeService.class));
        CodeGraphFirstRuntime.prepare(new ProcessBuilder("/usr/bin/true"), threadId, taskId);

        ToolOutcome outcome = handlers.codegraphExplore(
                new CodeGraphToolHandlers.CodeGraphExploreArgs("AuthToken", null, "symbol"),
                new ToolCall(threadId, null, AgentRole.TASK, taskId, null));

        assertThat(outcome).isEqualTo(ToolOutcome.Completed.ok("symbol result"));
        verify(codeGraph).query(checkout.toAbsolutePath().normalize(), "AuthToken");
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, taskId))
                .isEqualTo(new CodeGraphFirstRuntime.Metrics(0, 1, 1, 0, 0, 0));
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isFalse();
    }

    @Test
    void aRealUnavailableGraphAttemptUnlocksFallbackAndRecordsFailure(@TempDir Path checkout)
            throws Exception
    {
        Files.createDirectory(checkout.resolve(".git"));
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-" + suffix;
        String taskId = "task-" + suffix;
        Task task = mock(Task.class);
        when(task.worktreePath()).thenReturn(checkout.toString());
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTaskById(taskId)).thenReturn(Optional.of(task));
        CodeGraphUpdateCoordinator codeGraph = mock(CodeGraphUpdateCoordinator.class);
        when(codeGraph.explore(checkout.toAbsolutePath().normalize(), "map auth"))
                .thenThrow(new IllegalStateException("index unavailable"));
        CodeGraphToolHandlers handlers = new CodeGraphToolHandlers(
                codeGraph, tasks, mock(ThreadStore.class), mock(WorkspaceService.class),
                mock(WatchedRepoStore.class), mock(WorktreeService.class));
        CodeGraphFirstRuntime.prepare(new ProcessBuilder("/usr/bin/true"), threadId, taskId);

        ToolOutcome outcome = handlers.codegraphExplore(
                new CodeGraphToolHandlers.CodeGraphExploreArgs("map auth", null),
                new ToolCall(threadId, null, AgentRole.TASK, taskId, null));

        assertThat(outcome).isEqualTo(ToolOutcome.Completed.error(
                "CodeGraph unavailable: index unavailable"));
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isFalse();
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, taskId))
                .isEqualTo(new CodeGraphFirstRuntime.Metrics(0, 1, 0, 1, 1, 0));
    }

    @Test
    void anInvalidModeDoesNotUnlockNativeSearch()
    {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-" + suffix;
        String taskId = "task-" + suffix;
        CodeGraphToolHandlers handlers = new CodeGraphToolHandlers(
                mock(CodeGraphUpdateCoordinator.class), mock(TaskStore.class), mock(ThreadStore.class),
                mock(WorkspaceService.class), mock(WatchedRepoStore.class),
                mock(WorktreeService.class));
        CodeGraphFirstRuntime.prepare(new ProcessBuilder("/usr/bin/true"), threadId, taskId);

        ToolOutcome outcome = handlers.codegraphExplore(
                new CodeGraphToolHandlers.CodeGraphExploreArgs("AuthToken", null, "literal"),
                new ToolCall(threadId, null, AgentRole.TASK, taskId, null));

        assertThat(outcome).isEqualTo(ToolOutcome.Completed.error(
                "mode must be 'explore' or 'symbol'"));
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isTrue();
    }
}
