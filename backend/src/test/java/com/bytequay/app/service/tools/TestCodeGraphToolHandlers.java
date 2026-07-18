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

import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCodeGraphToolHandlers
{
    @Test
    void aValidGraphAttemptUnlocksNativeSearchEvenWhenNoCheckoutIsAvailable()
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
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, taskId)).isFalse();
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
}
