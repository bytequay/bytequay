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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The listener tears down both branches only for a merged PR, skips the
 * remote delete once the branch is already stamped deleted, deletes nothing
 * for an unmerged / no-PR completion, and never lets a remote-delete failure
 * stop the local reap.
 */
class TestMergedTaskBranchCleanup
{
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final PRService prService = mock(PRService.class);
    private final PRPublishService prPublishService = mock(PRPublishService.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final MergedTaskBranchCleanup cleanup = new MergedTaskBranchCleanup(
            taskStore, prService, prPublishService, worktreeService, Runnable::run);

    @Test
    void deletesRemoteAndLocalWhenMergedAndNotYetStamped()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));
        when(prService.findByTask("task-1")).thenReturn(Optional.of(pr(PR.STATUS_MERGED, null)));

        cleanup.onTaskCleanup(new TaskCleanupEvent("task-1"));

        verify(prPublishService).deleteBranch("pr-1");
        verify(worktreeService).remove(Path.of("/tmp/repo"), "/tmp/wt", "dev/task-1");
    }

    @Test
    void skipsRemoteButStillReapsLocalWhenBranchAlreadyDeleted()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));
        when(prService.findByTask("task-1")).thenReturn(Optional.of(pr(PR.STATUS_MERGED, NOW)));

        cleanup.onTaskCleanup(new TaskCleanupEvent("task-1"));

        verify(prPublishService, never()).deleteBranch(any());
        verify(worktreeService).remove(Path.of("/tmp/repo"), "/tmp/wt", "dev/task-1");
    }

    @Test
    void deletesNothingWhenPrIsNotMerged()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));
        when(prService.findByTask("task-1")).thenReturn(Optional.of(pr(PR.STATUS_CLOSED, null)));

        cleanup.onTaskCleanup(new TaskCleanupEvent("task-1"));

        verify(prPublishService, never()).deleteBranch(any());
        verify(worktreeService, never()).remove(any(), any(), any());
    }

    @Test
    void deletesNothingWhenTaskHasNoPr()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));
        when(prService.findByTask("task-1")).thenReturn(Optional.empty());

        cleanup.onTaskCleanup(new TaskCleanupEvent("task-1"));

        verify(prPublishService, never()).deleteBranch(any());
        verify(worktreeService, never()).remove(any(), any(), any());
    }

    @Test
    void reapsLocalEvenWhenRemoteDeleteThrows()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));
        when(prService.findByTask("task-1")).thenReturn(Optional.of(pr(PR.STATUS_MERGED, null)));
        doThrow(new RuntimeException("404 branch already gone"))
                .when(prPublishService).deleteBranch("pr-1");

        cleanup.onTaskCleanup(new TaskCleanupEvent("task-1"));

        verify(worktreeService).remove(Path.of("/tmp/repo"), "/tmp/wt", "dev/task-1");
    }

    private static Task task()
    {
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.COMPLETED,
                "dev/task-1", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }

    private static PR pr(String status, Instant branchDeletedAt)
    {
        return new PR(
                "pr-1", "task-1", "dev/task-1", "main", "Title", "Body", status, NOW,
                NOW, 42, "https://github.com/o/r/pull/42", NOW, null, null,
                PR.ORIGIN_TASK, "o/r", "chenjian2664", NOW, null, branchDeletedAt);
    }
}
