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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewTrunkLifecycleService
{
    @Test
    void archivesOnMergeAndReactivatesTheSameTrunkOnReopen()
    {
        ThreadStore threads = mock(ThreadStore.class);
        ReviewTrunkLifecycleService service =
                new ReviewTrunkLifecycleService(threads);
        Thread open = reviewTrunk(ThreadStatus.IDLE);
        when(threads.findReviewTrunk("ws-1", "acme/widget#42"))
                .thenReturn(Optional.of(open));

        service.reconcile("ws-1", pullRequest("merged", Instant.now()));

        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threads).saveThread(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(open.id());
        assertThat(saved.getValue().status()).isEqualTo(ThreadStatus.ARCHIVED);
        assertThat(saved.getValue().endedAt()).isNotNull();

        Thread archived = saved.getValue();
        clearInvocations(threads);
        when(threads.findReviewTrunk("ws-1", "acme/widget#42"))
                .thenReturn(Optional.of(archived));
        service.reconcile("ws-1", pullRequest("open", null));

        saved = ArgumentCaptor.forClass(Thread.class);
        verify(threads).saveThread(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(open.id());
        assertThat(saved.getValue().status())
                .isEqualTo(ThreadStatus.IDLE);
        assertThat(saved.getValue().endedAt()).isNull();
    }

    private static Thread reviewTrunk(ThreadStatus status)
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new Thread(
                "review-trunk",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Review PR #42",
                status,
                null,
                0,
                0,
                0,
                now,
                now,
                null,
                null,
                ThreadFlow.REVIEW,
                "ws-1",
                null,
                null,
                1,
                null,
                "acme/widget#42");
    }

    private static PullRequest pullRequest(String state, Instant mergedAt)
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new PullRequest(
                42,
                "acme/widget",
                42,
                "Improve workspace",
                "jack",
                "https://github.com/acme/widget/pull/42",
                now,
                now,
                PullRequest.Origin.AUTHORED,
                List.of(),
                Map.of(),
                false,
                null,
                null,
                null,
                List.of(),
                null,
                0,
                0,
                0,
                null,
                state,
                "closed".equals(state) ? now : null,
                mergedAt,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                "feature/workspace");
    }
}
