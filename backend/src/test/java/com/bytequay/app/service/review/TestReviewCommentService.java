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

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.stage.StageSteeringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewCommentService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");

    private StageStore stageStore;
    private StageSteeringService steering;
    private ReviewRoundService reviewRounds;
    private ReviewCommentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        steering = mock(StageSteeringService.class);
        reviewRounds = mock(ReviewRoundService.class);
        service = new ReviewCommentServiceImpl(stageStore, steering, reviewRounds);
    }

    @Test
    void addPersistsALocalUserComment()
    {
        when(stageStore.saveReviewComment(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewComment saved = service.add("task-1", "src/Foo.java", 42, "fix this");

        ArgumentCaptor<ReviewComment> captor = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore).saveReviewComment(captor.capture());
        ReviewComment persisted = captor.getValue();
        assertThat(persisted.taskId()).isEqualTo("task-1");
        assertThat(persisted.file()).isEqualTo("src/Foo.java");
        assertThat(persisted.line()).isEqualTo(42);
        assertThat(persisted.body()).isEqualTo("fix this");
        assertThat(persisted.source()).isEqualTo(ReviewCommentSource.LOCAL_USER);
        assertThat(persisted.resolved()).isFalse();
        assertThat(persisted.remoteLink()).isNull();
        assertThat(persisted.id()).isNotNull();
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    void addRejectsABlankBody()
    {
        assertThatThrownBy(() -> service.add("task-1", "src/Foo.java", 1, "  "))
                .isInstanceOf(ResponseStatusException.class);
        verify(stageStore, never()).saveReviewComment(any());
    }

    @Test
    void listReturnsAllForTheTask()
    {
        ReviewComment c = comment(UUID.randomUUID(), "task-1", false);
        when(stageStore.findCommentsByTask("task-1")).thenReturn(List.of(c));

        assertThat(service.list("task-1")).containsExactly(c);
    }

    @Test
    void resolveFlipsTheFlag()
    {
        UUID id = UUID.randomUUID();
        service.resolve(id);
        verify(stageStore).setReviewCommentResolved(id, true);
    }

    @Test
    void reopenFlipsTheFlagBack()
    {
        UUID id = UUID.randomUUID();
        service.reopen(id);
        verify(stageStore).setReviewCommentResolved(id, false);
    }

    @Test
    void resolvingARoundCommentRecomputesThatRoundsStats()
    {
        UUID id = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "nit", NOW, ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/octo/repo/pull/7#discussion_r1", false, 1001L, roundId, null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));

        service.resolve(id);

        verify(reviewRounds).recomputeStats(roundId.toString());
    }

    @Test
    void resolvingALocalUserCommentWithNoRoundNeverTouchesReviewRounds()
    {
        UUID id = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "note", NOW, ReviewCommentSource.LOCAL_USER,
                null, false, null, /* roundId */ null, null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));

        service.resolve(id);

        verify(reviewRounds, never()).recomputeStats(any());
    }

    @Test
    void submitReviewSteersTheActiveDevStageWithUnresolvedComments()
    {
        UUID resolvedId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        ReviewComment resolved = comment(resolvedId, "task-1", true);
        ReviewComment open = new ReviewComment(openId, "task-1", "src/Foo.java", 7, "rename it",
                NOW, ReviewCommentSource.LOCAL_USER, null, false, null, null, null, null);
        when(stageStore.findCommentsBySource("task-1", ReviewCommentSource.LOCAL_USER))
                .thenReturn(List.of(resolved, open));
        UUID devStageId = UUID.randomUUID();
        when(stageStore.findStagesByTask("task-1")).thenReturn(List.of(
                stage(UUID.randomUUID(), StageType.PLAN_STAGE, StageState.CLOSED),
                stage(devStageId, StageType.DEVELOPMENT_STAGE, StageState.ACTIVE)));
        when(steering.steer(eq(devStageId), any())).thenReturn(new StageSteeringService.SteerResult("turn-9"));

        ReviewCommentService.SubmitResult result = service.submitReview("task-1");

        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.turnId()).isEqualTo("turn-9");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(steering).steer(eq(devStageId), text.capture());
        assertThat(text.getValue())
                .contains("Address these review comments before shipping")
                .contains("`src/Foo.java:7` — rename it")
                .contains(openId.toString())
                .doesNotContain(resolvedId.toString());
    }

    @Test
    void submitReviewIsANoOpWithNoUnresolvedComments()
    {
        when(stageStore.findCommentsBySource("task-1", ReviewCommentSource.LOCAL_USER))
                .thenReturn(List.of(comment(UUID.randomUUID(), "task-1", true)));

        ReviewCommentService.SubmitResult result = service.submitReview("task-1");

        assertThat(result.submitted()).isZero();
        assertThat(result.turnId()).isNull();
        verify(steering, never()).steer(any(), any());
    }

    private static ReviewComment comment(UUID id, String taskId, boolean resolved)
    {
        return new ReviewComment(id, taskId, "src/Foo.java", 1, "body",
                NOW, ReviewCommentSource.LOCAL_USER, null, resolved, null, null, null, null);
    }

    private static StageInstance stage(UUID id, StageType type, StageState state)
    {
        return new StageInstance(id, "task-1", type, state, NOW, null, null);
    }
}
