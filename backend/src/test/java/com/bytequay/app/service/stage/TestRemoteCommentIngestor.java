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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.repository.StageStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRemoteCommentIngestor
{
    private final StageStore stageStore = mock(StageStore.class);
    private final RemoteCommentIngestor ingestor = new RemoteCommentIngestor(stageStore);

    @Test
    void ingestsUnstoredRemoteCommentsAsRemoteReviewerRows()
    {
        PullRequestDetail detail = detailWith(thread("src/Foo.java", 12, false,
                message(1001L, "nit: rename")));
        when(stageStore.reviewCommentExistsByRemoteLink(anyString())).thenReturn(false);

        ingestor.ingest("task-1", "octo/repo", 7, detail);

        ArgumentCaptor<ReviewComment> captor = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore).saveReviewComment(captor.capture());
        ReviewComment saved = captor.getValue();
        assertThat(saved.source()).isEqualTo(ReviewCommentSource.REMOTE_REVIEWER);
        assertThat(saved.file()).isEqualTo("src/Foo.java");
        assertThat(saved.line()).isEqualTo(12);
        assertThat(saved.body()).isEqualTo("nit: rename");
        assertThat(saved.remoteLink())
                .isEqualTo("https://github.com/octo/repo/pull/7#discussion_r1001");
        assertThat(saved.taskId()).isEqualTo("task-1");
        assertThat(saved.remoteCommentId()).isEqualTo(1L);
    }

    @Test
    void excludesTheAuthenticatedUsersInlineRepliesAndTopLevelComments()
    {
        ReviewThread thread = new ReviewThread(
                1001L, "src/Foo.java", 12, "RIGHT", null,
                List.of(
                        message(1001L, "reviewer", "please fix"),
                        message(1002L, "octocat", "Fixed, thanks!")),
                false, null, false, null, null, null, null);
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.reviewThreads()).thenReturn(List.of(thread));
        when(detail.recentActivity()).thenReturn(List.of(
                commented(5001L, "octocat", "author follow-up", Instant.parse("2026-06-20T10:00:00Z"))));

        ingestor.ingest("task-1", "octo/repo", 7, detail, "@OctoCat");

        ArgumentCaptor<ReviewComment> captor = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore).saveReviewComment(captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("please fix");
        assertThat(captor.getValue().remoteCommentId()).isEqualTo(1001L);
    }

    @Test
    void refreshesAnExistingUnroundedThreadsResolutionInBothDirections()
    {
        String link = "https://github.com/octo/repo/pull/7#discussion_r1001";
        ReviewComment unresolved = remoteComment(false, link);
        when(stageStore.findReviewCommentByRemoteLink(link))
                .thenReturn(Optional.of(unresolved), Optional.of(unresolved.withRemoteState(true, 1L)));

        ingestor.ingest("task-1", "octo/repo", 7,
                detailWith(thread("src/Foo.java", 12, true, message(1001L, "nit"))));
        ingestor.ingest("task-1", "octo/repo", 7,
                detailWith(thread("src/Foo.java", 12, false, message(1001L, "nit"))));

        ArgumentCaptor<ReviewComment> captor = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore, times(2)).saveReviewComment(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReviewComment::resolved)
                .containsExactly(true, false);
    }

    @Test
    void skipsCommentsAlreadyStored()
    {
        PullRequestDetail detail = detailWith(thread("src/Foo.java", 12, false,
                message(1001L, "already here")));
        when(stageStore.reviewCommentExistsByRemoteLink(anyString())).thenReturn(true);

        ingestor.ingest("task-1", "octo/repo", 7, detail);

        verify(stageStore, never()).saveReviewComment(any());
    }

    @Test
    void skipsThreadsWithoutAnchoredFile()
    {
        PullRequestDetail detail = detailWith(thread(null, null, false, message(2002L, "pr-level")));

        ingestor.ingest("task-1", "octo/repo", 7, detail);

        verify(stageStore, never()).saveReviewComment(any());
    }

    @Test
    void ingestsTopLevelIssueCommentsAsUnanchoredRemoteReviewerRows()
    {
        // A plain comment left on the PR's Conversation tab — no file/line,
        // no thread — arrives as a "commented" activity event instead of a
        // review thread.
        PullRequestDetail detail = detailWithActivity(List.of(
                commented(5001L, "octocat", "Can you also handle the empty-list case?",
                        Instant.parse("2026-06-20T10:00:00Z"))));
        when(stageStore.reviewCommentExistsByRemoteLink(anyString())).thenReturn(false);

        ingestor.ingest("task-1", "octo/repo", 7, detail);

        ArgumentCaptor<ReviewComment> captor = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore).saveReviewComment(captor.capture());
        ReviewComment saved = captor.getValue();
        assertThat(saved.source()).isEqualTo(ReviewCommentSource.REMOTE_REVIEWER);
        assertThat(saved.file()).isNull();
        assertThat(saved.line()).isZero();
        assertThat(saved.body()).isEqualTo("Can you also handle the empty-list case?");
        assertThat(saved.remoteLink())
                .isEqualTo("https://github.com/octo/repo/pull/7#issuecomment-5001");
    }

    @Test
    void ignoresNonCommentActivityEventsAndAlreadyStoredIssueComments()
    {
        PullRequestDetail detail = detailWithActivity(List.of(
                new ActivityItem("octocat", "reviewed", Instant.parse("2026-06-20T10:00:00Z"),
                        null, "APPROVED", null, null, null, null, "MEMBER", 9001L, null),
                commented(5001L, "octocat", "already seen", Instant.parse("2026-06-20T10:00:00Z"))));
        when(stageStore.reviewCommentExistsByRemoteLink(anyString())).thenReturn(true);

        ingestor.ingest("task-1", "octo/repo", 7, detail);

        verify(stageStore, never()).saveReviewComment(any());
    }

    @Test
    void aFailedSaveIsLoggedAndSkippedRatherThanBlockingSiblingCommentsOrPropagating()
    {
        // Two threads on the same PR: the first's save blows up (e.g. the
        // FK-constraint failure seen in production), the second must still
        // be ingested, and ingest() itself must not throw.
        PullRequestDetail detail = detailWith(List.of(
                thread("src/Foo.java", 12, false, message(1001L, "bad one")),
                thread("src/Bar.java", 5, false, message(2002L, "good one"))));
        when(stageStore.reviewCommentExistsByRemoteLink(anyString())).thenReturn(false);
        when(stageStore.saveReviewComment(any()))
                .thenThrow(new RuntimeException("SQLITE_CONSTRAINT_FOREIGNKEY"))
                .thenReturn(null);

        assertThatCode(() -> ingestor.ingest("task-1", "octo/repo", 7, detail)).doesNotThrowAnyException();

        verify(stageStore, times(2)).saveReviewComment(any());
    }

    private static PullRequestDetail detailWith(ReviewThread thread)
    {
        return detailWith(List.of(thread));
    }

    private static PullRequestDetail detailWith(List<ReviewThread> threads)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.reviewThreads()).thenReturn(threads);
        return detail;
    }

    private static PullRequestDetail detailWithActivity(List<ActivityItem> activity)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.recentActivity()).thenReturn(activity);
        return detail;
    }

    private static ActivityItem commented(long githubId, String actor, String body, Instant timestamp)
    {
        return new ActivityItem(actor, "commented", timestamp, body, null, null, null, null, null,
                "MEMBER", githubId, null);
    }

    private static ReviewThread thread(String filePath, Integer line, boolean resolved, ReviewMessage message)
    {
        return new ReviewThread(1L, filePath, line, "RIGHT", null, List.of(message),
                resolved, null, false, null, null, null, null);
    }

    private static ReviewMessage message(long githubId, String body)
    {
        return message(githubId, "octocat", body);
    }

    private static ReviewMessage message(long githubId, String author, String body)
    {
        return new ReviewMessage(githubId, author, body,
                Instant.parse("2026-06-20T10:00:00Z"), null, null, "MEMBER");
    }

    private static ReviewComment remoteComment(boolean resolved, String link)
    {
        return new ReviewComment(
                UUID.randomUUID(), "task-1", "src/Foo.java", 12, "nit",
                Instant.parse("2026-06-20T10:00:00Z"), ReviewCommentSource.REMOTE_REVIEWER,
                link, resolved, 1L, null, null, null, "RIGHT", null, null);
    }
}
