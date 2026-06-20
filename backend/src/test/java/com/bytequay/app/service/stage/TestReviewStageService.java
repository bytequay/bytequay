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

import com.bytequay.app.beans.stage.SpawnReviewResult;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.review.ReviewPassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the spawn path: validates the parent stage + task phase, opens
 * a callable REVIEW_STAGE under the caller, seats a TASK_PHASE pass, and
 * stamps the stage ↔ pass link. The heavy panel body is stubbed.
 */
class TestReviewStageService
{
    private static final Instant NOW = Instant.parse("2026-06-21T09:00:00Z");

    private StageStore stageStore;
    private TaskStore taskStore;
    private WatchedRepoStore watchedRepoStore;
    private ReviewPassService reviewPassService;
    private ReviewStageServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        taskStore = mock(TaskStore.class);
        watchedRepoStore = mock(WatchedRepoStore.class);
        reviewPassService = mock(ReviewPassService.class);
        service = new ReviewStageServiceImpl(
                stageStore, taskStore, watchedRepoStore, reviewPassService);
    }

    @Test
    void opensAReviewStageSeatsAPassAndStampsTheLink()
    {
        UUID parentId = UUID.randomUUID();
        UUID reviewStageId = UUID.randomUUID();
        StageInstance parent = stage(parentId, "task-7", StageType.DEVELOPMENT_STAGE, StageState.ACTIVE);
        StageInstance reviewStage = stage(reviewStageId, "task-7", StageType.REVIEW_STAGE, StageState.OPEN);

        when(stageStore.findStageById(parentId)).thenReturn(Optional.of(parent));
        when(taskStore.findTaskById("task-7"))
                .thenReturn(Optional.of(task("task-7", TaskPhase.INTERNAL_REVIEW, 42, "/repos/widget")));
        when(watchedRepoStore.findAll()).thenReturn(List.of(repo("acme", "widget", "/repos/widget")));
        when(stageStore.openStage("task-7", StageType.REVIEW_STAGE, parentId)).thenReturn(reviewStage);
        when(reviewPassService.startTaskPhaseReview(
                eq("task-7"), eq("acme/widget"), eq(42), eq(ReviewPassKind.FRESH), any(),
                eq(reviewStageId.toString())))
                .thenReturn(detail("pass-9", "thread-3"));

        SpawnReviewResult result = service.spawnReview(parentId);

        assertThat(result.reviewStageId()).isEqualTo(reviewStageId.toString());
        assertThat(result.reviewPassId()).isEqualTo("pass-9");
        assertThat(result.reviewThreadId()).isEqualTo("thread-3");
        verify(stageStore).openStage("task-7", StageType.REVIEW_STAGE, parentId);
        // The pass is linked to the stage during seating (race-free), so the
        // service passes the stage id straight into the start call.
        verify(reviewPassService).startTaskPhaseReview(
                eq("task-7"), eq("acme/widget"), eq(42), eq(ReviewPassKind.FRESH), any(),
                eq(reviewStageId.toString()));
    }

    @Test
    void rejectsAClosedParentStage()
    {
        UUID parentId = UUID.randomUUID();
        when(stageStore.findStageById(parentId)).thenReturn(Optional.of(
                stage(parentId, "task-7", StageType.DEVELOPMENT_STAGE, StageState.CLOSED)));

        assertThatThrownBy(() -> service.spawnReview(parentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("closed");
        verify(stageStore, never()).openStage(any(), any(), any());
    }

    @Test
    void rejectsATaskOutsideAnInternalReviewPhase()
    {
        UUID parentId = UUID.randomUUID();
        when(stageStore.findStageById(parentId)).thenReturn(Optional.of(
                stage(parentId, "task-7", StageType.DEVELOPMENT_STAGE, StageState.ACTIVE)));
        when(taskStore.findTaskById("task-7"))
                .thenReturn(Optional.of(task("task-7", TaskPhase.IMPLEMENTING, 42, "/repos/widget")));

        assertThatThrownBy(() -> service.spawnReview(parentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("internal-review");
        verify(reviewPassService, never())
                .startTaskPhaseReview(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void rejectsATaskWithoutAPr()
    {
        UUID parentId = UUID.randomUUID();
        when(stageStore.findStageById(parentId)).thenReturn(Optional.of(
                stage(parentId, "task-7", StageType.DEVELOPMENT_STAGE, StageState.ACTIVE)));
        when(taskStore.findTaskById("task-7"))
                .thenReturn(Optional.of(task("task-7", TaskPhase.INTERNAL_REVIEW, null, "/repos/widget")));

        assertThatThrownBy(() -> service.spawnReview(parentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no PR");
    }

    private static StageInstance stage(UUID id, String taskId, StageType type, StageState state)
    {
        return new StageInstance(id, taskId, type, state, NOW, null, null);
    }

    private static WatchedRepo repo(String owner, String name, String localClonePath)
    {
        return new WatchedRepo(1L, owner, name, 0, localClonePath, "origin", null);
    }

    private static Task task(String id, TaskPhase phase, Integer prNumber, String workingDir)
    {
        return new Task(
                id, "thread-" + id, 1L, TaskStatus.RUNNING, "feature", null, "main", workingDir,
                null, null, prNumber, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null, phase, null, 0, null);
    }

    private static ReviewPassDetail detail(String passId, String threadId)
    {
        ReviewPass pass = new ReviewPass(
                passId, threadId, "acme/widget", 42, "sha", ReviewPhase.INDEPENDENT,
                0, 3, 500L, 0L, null, NOW, null);
        return new ReviewPassDetail(pass, null, List.of(), List.of(), List.of(), List.of());
    }
}
