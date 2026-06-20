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
package com.bytequay.app.repository;

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for Task stages, their lifecycle events, and the
 * unified review comments. Mirrors {@link TaskStore} in shape; the service
 * layer talks only to this interface.
 *
 * <p>{@link #openStage} and {@link #closeStage} also write the matching
 * {@code OPENED} / {@code CLOSED} row to {@code task_stage_event}, so a
 * caller never has to remember to log the lifecycle separately.
 */
public interface StageStore
{
    // ── stages ─────────────────────────────────────────────────────────

    /** Open a fresh stage of {@code type} for {@code taskId} in state
     *  {@code OPEN}, writing an {@code OPENED} event. {@code callerStageId}
     *  is set only for a callable sub-stage. */
    StageInstance openStage(String taskId, StageType type, UUID callerStageId);

    /** Close {@code stageId} (state {@code CLOSED}, {@code closedAt} now),
     *  writing a {@code CLOSED} event carrying {@code reason}. No-op when
     *  the id is unknown or the stage is already closed. */
    void closeStage(UUID stageId, String reason);

    Optional<StageInstance> findStageById(UUID stageId);

    /** A task's stages, oldest-first. */
    List<StageInstance> findStagesByTask(String taskId);

    /** The latest stage in state {@code OPEN} or {@code ACTIVE} — the one a
     *  cross-cutting phase attaches to, and the one the phase-transition
     *  hook compares against. */
    Optional<StageInstance> findActiveStage(String taskId);

    // ── stage events ───────────────────────────────────────────────────

    /** Append a stage event beyond the {@code OPENED}/{@code CLOSED} pair
     *  the lifecycle writes itself — used by the mutex, budget, and notify
     *  paths. Stamped with the current time. */
    StageEvent recordEvent(UUID stageId, String taskId, StageEventType type, Map<String, Object> payload);

    /** A stage's events, oldest-first. */
    List<StageEvent> findEventsByStage(UUID stageId);

    /** A stage's most recent events, newest-first, capped at {@code limit}. */
    List<StageEvent> findRecentEventsByStage(UUID stageId, int limit);

    /** A task's events across all its stages, oldest-first. */
    List<StageEvent> findEventsByTask(String taskId);

    // ── review comments ────────────────────────────────────────────────

    /** Insert or update a review comment by id. */
    ReviewComment saveReviewComment(ReviewComment comment);

    Optional<ReviewComment> findReviewCommentById(UUID id);

    /** A task's unresolved comments, any source. */
    List<ReviewComment> findUnresolvedComments(String taskId);

    /** A task's comments of a single source. */
    List<ReviewComment> findCommentsBySource(String taskId, ReviewCommentSource source);
}
