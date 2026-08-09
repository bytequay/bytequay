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
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.WorkModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for Task stages, their lifecycle events, and the
 * unified review comments. Mirrors {@link TaskStore} in shape; the service
 * layer talks only to this interface.
 *
 * <p>The raw lifecycle methods are persistence primitives for
 * {@code StageStateMachine}. Production services must use that machine so
 * task ownership, command serialization, audit, and close notifications are
 * applied together. The compatibility close/reopen primitives remain for
 * store-level tests and legacy repair only.
 */
public interface StageStore
{
    // ── stages ─────────────────────────────────────────────────────────

    /** Machine-only insert of a fresh stage of {@code type} for {@code taskId} in state
     *  {@code OPEN}, writing an {@code OPENED} event. {@code callerStageId}
     *  is set only for a callable sub-stage. */
    StageInstance openStage(String taskId, StageType type, UUID callerStageId);

    /** Legacy/store-level close primitive. Close {@code stageId} (state {@code CLOSED}, {@code closedAt} now),
     *  writing a {@code CLOSED} event carrying {@code reason}. No-op when
     *  the id is unknown or the stage is already closed. */
    void closeStage(UUID stageId, String reason);

    /** Legacy/store-level close primitive merging {@code extraPayload} into the single
     *  {@code CLOSED} event alongside {@code reason} — so a caller (e.g. a
     *  finished review panel) can record its summary on the closing event
     *  rather than as a second row. No-op when unknown or already closed. */
    void closeStage(UUID stageId, String reason, Map<String, Object> extraPayload);

    /** Legacy/store-level primitive that wakes a {@code CLOSED} stage back up
     *  of work: state → {@code OPEN}, {@code closedAt} cleared, writing a
     *  {@code REOPENED} event. Lets a later CI-fix / review-round / guard
     *  tick reuse the stage id (and whatever agent session is cached under
     *  it) instead of opening a second stage. No-op (returns the row
     *  unchanged) when the stage is unknown or not currently closed. */
    StageInstance reopenStage(UUID stageId);

    /** Compare-and-set the lifecycle columns alone: state → {@code to}
     *  (with {@code closedAt} stamped or cleared to match) only while the
     *  row still holds {@code expected}. Writes no event and never touches
     *  metadata columns — the stage machine composes the audit.
     *
     *  @return true when the row was updated */
    boolean updateStateIf(UUID stageId, StageState expected, StageState to, Instant closedAt);

    Optional<StageInstance> findStageById(UUID stageId);

    /** The raw {@code metrics_json} blob for a stage, or empty if unknown. */
    Optional<String> findMetricsJson(UUID stageId);

    /** Overwrite a stage's {@code metrics_json} blob. No-op when unknown. */
    void updateMetricsJson(UUID stageId, String metricsJson);

    /** Set (or clear, with {@code null}) the stage's override on the
     *  work-model cascade (V159). No-op when the id is unknown. */
    void updateWorkModel(UUID stageId, WorkModel workModel);

    /** A task's stages, oldest-first. */
    List<StageInstance> findStagesByTask(String taskId);

    /** The latest stage in state {@code OPEN} — the one a cross-cutting phase
     *  attaches to, and the one the phase-transition hook compares against. */
    Optional<StageInstance> findActiveStage(String taskId);

    /** The task's most-recent {@code OPEN} stage of the given type. Lets the
     *  shipped-CI-fix / comment-addressing turns pin their stage id explicitly
     *  so their messages land in {@code stage_messages} rather than leaking to
     *  the thread slice. */
    default Optional<StageInstance> findLiveStageByType(String taskId, StageType type)
    {
        return findStagesByTask(taskId).stream()
                .filter(s -> s.type() == type && s.state() != StageState.CLOSED)
                .reduce((first, second) -> second);
    }

    /** The task's stage of this exact type, regardless of state (including
     *  {@code CLOSED}) — the reuse check a caller runs before minting a new
     *  stage, so a second burst of the same kind of work (another CI-fix
     *  attempt, another review round, tomorrow's guard tick) wakes the
     *  existing stage back up instead of duplicating it. */
    default Optional<StageInstance> findStageByType(String taskId, StageType type)
    {
        return findStagesByTask(taskId).stream()
                .filter(s -> s.type() == type)
                .reduce((first, second) -> second);
    }

    // ── stage events ───────────────────────────────────────────────────

    /** Append a stage event beyond the {@code OPENED}/{@code CLOSED} pair
     *  the lifecycle writes itself — used by the mutex, budget, and notify
     *  paths. Stamped with the current time. */
    StageEvent recordEvent(UUID stageId, String taskId, StageEventType type, Map<String, Object> payload);

    /** Find one stage event by id. */
    Optional<StageEvent> findEventById(UUID eventId);

    /** Overwrite an event's JSON payload (used to flip a follow-up note's
     *  status to addressed / dismissed). No-op when the id is unknown. */
    void updateEventPayload(UUID eventId, Map<String, Object> payload);

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

    /** Finds the persisted mirror of one GitHub comment. */
    default Optional<ReviewComment> findReviewCommentByRemoteLink(String remoteLink)
    {
        return Optional.empty();
    }

    /** Whether a remote-sourced comment with this link is already stored —
     *  the dedup guard for remote-comment ingestion. */
    boolean reviewCommentExistsByRemoteLink(String remoteLink);

    /** A task's unresolved comments, any source. */
    List<ReviewComment> findUnresolvedComments(String taskId);

    /** A task's comments of a single source. */
    List<ReviewComment> findCommentsBySource(String taskId, ReviewCommentSource source);

    /** All of a task's review comments, any source, oldest-first. The
     *  diff page reads this to overlay every comment (resolved or not). */
    default List<ReviewComment> findCommentsByTask(String taskId)
    {
        return List.of();
    }

    /** Flip a comment's {@code resolved} flag. No-op when the id is
     *  unknown. */
    default void setReviewCommentResolved(UUID id, boolean resolved)
    {
    }

    /** Whether the round gate has already resolved this inline thread on GitHub. */
    default boolean isRemoteThreadResolutionPosted(UUID commentId)
    {
        return false;
    }

    /** Durable checkpoint written after GitHub accepts the thread-resolution mutation. */
    default void markRemoteThreadResolutionPosted(UUID commentId, Instant postedAt)
    {
    }

    /** A round's assigned comments, oldest-first. */
    List<ReviewComment> findCommentsByRound(UUID roundId);

    /** Assign a batch of comments to a round in one go. */
    void assignCommentsToRound(List<UUID> commentIds, UUID roundId);
}
