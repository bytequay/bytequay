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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCommit;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for materialising a task's local PR from git: it creates the row,
 * appends unseen branch commits oldest-first (deduping ones already recorded),
 * and flips to {@code local-open} once the task is awaiting review.
 */
class TestLocalPRSyncService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final LocalPRService localPr = mock(LocalPRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final LocalPRSyncService service = new LocalPRSyncService(localPr, taskStore, git);

    private Task task(TaskPhase phase)
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, phase, null, 0, null);
    }

    private LocalPR draftPr()
    {
        return LocalPR.create("pr1", "task1", "feature/x", "main", "T", "", NOW);
    }

    private static GitRunner.CommitEntry commit(String shortSha, String subject)
    {
        return new GitRunner.CommitEntry(
                shortSha + "full", shortSha, "you", "you@example.com", "2026-07-01T00:00:00Z", subject);
    }

    /** Stub every commit's numstat to a fixed +10 −2 delta. */
    private void stubDelta()
            throws Exception
    {
        when(git.commitFiles(any(), any()))
                .thenReturn(List.of(new GitRunner.CommitFileChange("f.java", "M", 10, 2)));
    }

    @Test
    void createsThePrAndRecordsBranchCommitsOldestFirst()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(localPr.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(draftPr());
        when(localPr.commits("pr1")).thenReturn(List.of());
        when(localPr.findById("pr1")).thenReturn(Optional.of(draftPr()));
        // git log is newest-first.
        when(git.listCommitsAhead(any(), eq("main"), eq(200)))
                .thenReturn(List.of(commit("ccc", "third"), commit("bbb", "second"), commit("aaa", "first")));
        stubDelta();

        service.syncFromTask("task1");

        // Recorded oldest-first: aaa, bbb, ccc — each with its summed numstat delta.
        var order = inOrder(localPr);
        order.verify(localPr).recordCommit(eq("pr1"), eq("aaa"), eq("first"), eq(10), eq(2), any());
        order.verify(localPr).recordCommit(eq("pr1"), eq("bbb"), eq("second"), eq(10), eq(2), any());
        order.verify(localPr).recordCommit(eq("pr1"), eq("ccc"), eq("third"), eq(10), eq(2), any());
    }

    @Test
    void skipsCommitsAlreadyRecorded()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(localPr.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(localPr.findById("pr1")).thenReturn(Optional.of(draftPr()));
        when(localPr.commits("pr1")).thenReturn(List.of(new LocalPRCommit(
                "id-aaa", "pr1", "aaa", "first", 0, 0, NOW, null)));
        when(git.listCommitsAhead(any(), eq("main"), eq(200)))
                .thenReturn(List.of(commit("bbb", "second"), commit("aaa", "first")));
        stubDelta();

        service.syncFromTask("task1");

        verify(localPr).recordCommit(eq("pr1"), eq("bbb"), eq("second"), eq(10), eq(2), any());
        verify(localPr, never()).recordCommit(eq("pr1"), eq("aaa"), any(), anyInt(), anyInt(), any());
    }

    @Test
    void returnsEmptyWhenTheTaskHasNoBranch()
    {
        Task noBranch = new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                null, null, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null, TaskPhase.QUEUED, null, 0, null);
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(noBranch));

        assertThat(service.syncFromTask("task1")).isEmpty();
        verify(localPr, never()).createForTask(any(), any(), any(), any(), any());
    }

    @Test
    void flipsToLocalOpenOnceAwaitingReview()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.AWAITING_PUSH)));
        when(localPr.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(localPr.commits("pr1")).thenReturn(List.of());
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(localPr.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(localPr).requestUserReview(eq("pr1"), any());
    }

    @Test
    void doesNotFlipWhileStillImplementing()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(localPr.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(localPr.commits("pr1")).thenReturn(List.of());
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(localPr.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(localPr, never()).requestUserReview(any(), any());
    }
}
