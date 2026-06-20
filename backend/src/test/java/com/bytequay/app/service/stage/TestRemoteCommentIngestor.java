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
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.repository.StageStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static PullRequestDetail detailWith(ReviewThread thread)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.reviewThreads()).thenReturn(List.of(thread));
        return detail;
    }

    private static ReviewThread thread(String filePath, Integer line, boolean resolved, ReviewMessage message)
    {
        return new ReviewThread(1L, filePath, line, "RIGHT", null, List.of(message),
                resolved, false, null, null, null, null);
    }

    private static ReviewMessage message(long githubId, String body)
    {
        return new ReviewMessage(githubId, "octocat", body,
                Instant.parse("2026-06-20T10:00:00Z"), null, null, "MEMBER");
    }
}
