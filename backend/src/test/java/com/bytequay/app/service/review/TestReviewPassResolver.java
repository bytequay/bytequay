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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewPassResolver
{
    private ThreadStore threadStore;
    private TaskStore taskStore;
    private ReviewStore reviewStore;
    private GitRunner git;
    private ReviewBuildSelectionStore selections;
    private ReviewPassResolver resolver;

    @BeforeEach
    void setUp()
    {
        threadStore = mock(ThreadStore.class);
        taskStore = mock(TaskStore.class);
        reviewStore = mock(ReviewStore.class);
        git = mock(GitRunner.class);
        selections = mock(ReviewBuildSelectionStore.class);
        resolver = new ReviewPassResolver(
                threadStore, taskStore, reviewStore, git, selections);
    }

    @Test
    void resolveFromTextsFlipsReferencedAgreedFindingsAndStampsTheBuildThread()
    {
        when(threadStore.findThreadById("bt")).thenReturn(Optional.of(buildThread("pass-1")));
        when(reviewStore.listFindingsForPass("pass-1")).thenReturn(List.of(
                finding("f1", ReviewFindingStatus.AGREED),
                finding("f2", ReviewFindingStatus.AGREED)));

        int flipped = resolver.resolveFromTexts("bt", List.of("fixed it, see #finding-f1"));

        assertThat(flipped).isEqualTo(1);
        ArgumentCaptor<ReviewFinding> saved = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewStore).saveFinding(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo("f1");
        assertThat(saved.getValue().status()).isEqualTo(ReviewFindingStatus.RESOLVED);
        assertThat(saved.getValue().resolution()).isEqualTo("build_thread_bt");
    }

    @Test
    void ignoresRefsToFindingsOutsideTheParentPass()
    {
        when(threadStore.findThreadById("bt")).thenReturn(Optional.of(buildThread("pass-1")));
        when(reviewStore.listFindingsForPass("pass-1")).thenReturn(List.of(
                finding("f1", ReviewFindingStatus.AGREED)));

        // #finding-9999 belongs to some other pass — silently skipped.
        int flipped = resolver.resolveFromTexts("bt", List.of("see #finding-9999 and #finding-f1"));

        assertThat(flipped).isEqualTo(1);
        verify(reviewStore).saveFinding(any());
    }

    @Test
    void isIdempotentAndOnlyTouchesAgreedFindings()
    {
        when(threadStore.findThreadById("bt")).thenReturn(Optional.of(buildThread("pass-1")));
        when(reviewStore.listFindingsForPass("pass-1")).thenReturn(List.of(
                finding("f1", ReviewFindingStatus.RESOLVED),    // already resolved
                finding("f2", ReviewFindingStatus.DISPUTED)));   // not agreed

        int flipped = resolver.resolveFromTexts("bt", List.of("#finding-f1 #finding-f2"));

        assertThat(flipped).isZero();
        verify(reviewStore, never()).saveFinding(any());
    }

    @Test
    void noOpWhenThreadHasNoParentReviewPass()
    {
        when(threadStore.findThreadById("plain")).thenReturn(Optional.of(buildThread(null)));
        assertThat(resolver.resolveFromTexts("plain", List.of("#finding-f1"))).isZero();
        verify(reviewStore, never()).saveFinding(any());
    }

    @Test
    void onPublishApprovedResolvesFromASuggestedChangeCommentBody()
    {
        when(threadStore.findThreadById("bt")).thenReturn(Optional.of(buildThread("pass-1")));
        when(reviewStore.listFindingsForPass("pass-1")).thenReturn(List.of(
                finding("f1", ReviewFindingStatus.AGREED)));

        int flipped = resolver.onPublishApproved("bt", "post_comment",
                "Suggested change addressing #finding-f1");

        assertThat(flipped).isEqualTo(1);
        verify(reviewStore).saveFinding(any());
    }

    @Test
    void onPublishApprovedResolvesFromTheThreadsCommitSubjects()
            throws Exception
    {
        Task task = buildTask("build-branch", "/clones/widget");
        when(threadStore.findThreadById("bt")).thenReturn(Optional.of(buildThread("pass-1")));
        when(taskStore.activeTasksForThread("bt")).thenReturn(List.of(task));
        when(reviewStore.listFindingsForPass("pass-1")).thenReturn(List.of(
                finding("f1", ReviewFindingStatus.AGREED)));
        when(git.listCommits(any(), eq("build-branch"), anyInt())).thenReturn(List.of(
                new GitRunner.CommitEntry(
                        "sha", "sh", "me", "me@x", "2026", "2026",
                        "Address #finding-f1 per @claude")));

        int flipped = resolver.onPublishApproved("bt", "ship_task", null);

        assertThat(flipped).isEqualTo(1);
        verify(reviewStore).saveFinding(any());
    }

    @Test
    void v2ReviewBuildIgnoresPublishAndTextReferences()
    {
        when(threadStore.findThreadById("bt"))
                .thenReturn(Optional.of(buildThread("pass-1")));
        when(selections.find("bt")).thenReturn(Optional.of(mock(
                ReviewBuildSelectionStore.Selection.class)));

        assertThat(resolver.resolveFromTexts(
                "bt", List.of("#finding-f1"))).isZero();
        assertThat(resolver.onPublishApproved(
                "bt", "ship_task", "#finding-f1")).isZero();
        verify(reviewStore, never()).listFindingsForPass(any());
        verify(reviewStore, never()).saveFinding(any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static ReviewFinding finding(String id, ReviewFindingStatus status)
    {
        return new ReviewFinding(id, "pass-1", "src/a.ts", 1, ReviewFindingSeverity.MAJOR,
                status, "body", null, null, Instant.EPOCH, null, 0);
    }

    private static Thread buildThread(String parentReviewPassId)
    {
        return new Thread("bt", ThreadKind.CLI_AGENT, null, null, "title", null, null,
                0L, 0L, 0L, Instant.EPOCH, Instant.EPOCH, null, null, ThreadFlow.BUILD,
                "ws-1", null, parentReviewPassId);
    }

    private static Task buildTask(String branchName, String worktreePath)
    {
        return new Task("task-1", "bt", 1L, null, branchName, worktreePath, "main", worktreePath,
                null, null, null, null, null, null, null, null, 0L, 0L, 0L, null,
                Instant.EPOCH, null, null, "name", null, null);
    }
}
