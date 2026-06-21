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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteStageStore
        implements StageStore
{
    /** OPEN + ACTIVE — the two non-terminal, non-paused states that count
     *  as "the currently active stage". */
    private static final List<String> ACTIVE_STATES =
            List.of(StageState.OPEN.name(), StageState.ACTIVE.name());

    private final TaskStageJpaRepository stages;
    private final TaskStageEventJpaRepository events;
    private final ReviewCommentJpaRepository comments;
    private final ObjectMapper objectMapper;

    SqliteStageStore(
            TaskStageJpaRepository stages,
            TaskStageEventJpaRepository events,
            ReviewCommentJpaRepository comments,
            ObjectMapper objectMapper)
    {
        this.stages = requireNonNull(stages, "stages is null");
        this.events = requireNonNull(events, "events is null");
        this.comments = requireNonNull(comments, "comments is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    @Transactional
    public StageInstance openStage(String taskId, StageType type, UUID callerStageId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(type, "type is null");
        Instant now = Instant.now();
        TaskStageEntity row = new TaskStageEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTaskId(taskId);
        row.setStageType(type.name());
        row.setState(StageState.OPEN.name());
        row.setOpenedAtMs(now.toEpochMilli());
        row.setCallerStageId(callerStageId == null ? null : callerStageId.toString());
        row.setSummaryJson("{}");
        row.setMetricsJson("{}");
        stages.save(row);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stageType", type.name());
        if (callerStageId != null) {
            payload.put("callerStageId", callerStageId.toString());
        }
        writeEvent(row.getId(), taskId, StageEventType.OPENED, payload, now);
        return toStage(row);
    }

    @Override
    @Transactional
    public void closeStage(UUID stageId, String reason)
    {
        closeStage(stageId, reason, Map.of());
    }

    @Override
    @Transactional
    public void closeStage(UUID stageId, String reason, Map<String, Object> extraPayload)
    {
        requireNonNull(stageId, "stageId is null");
        requireNonNull(extraPayload, "extraPayload is null");
        stages.findById(stageId.toString()).ifPresent(row -> {
            if (StageState.CLOSED.name().equals(row.getState())) {
                return;
            }
            Instant now = Instant.now();
            row.setState(StageState.CLOSED.name());
            row.setClosedAtMs(now.toEpochMilli());
            stages.save(row);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason == null ? "" : reason);
            payload.putAll(extraPayload);
            writeEvent(row.getId(), row.getTaskId(), StageEventType.CLOSED, payload, now);
        });
    }

    @Override
    public Optional<StageInstance> findStageById(UUID stageId)
    {
        return stages.findById(stageId.toString()).map(SqliteStageStore::toStage);
    }

    @Override
    public Optional<String> findMetricsJson(UUID stageId)
    {
        return stages.findById(stageId.toString()).map(TaskStageEntity::getMetricsJson);
    }

    @Override
    @Transactional
    public void updateMetricsJson(UUID stageId, String metricsJson)
    {
        stages.findById(stageId.toString()).ifPresent(row -> {
            row.setMetricsJson(metricsJson);
            stages.save(row);
        });
    }

    @Override
    public List<StageInstance> findStagesByTask(String taskId)
    {
        return stages.findByTaskIdOrderByOpenedAtMsAsc(taskId).stream()
                .map(SqliteStageStore::toStage)
                .toList();
    }

    @Override
    public Optional<StageInstance> findActiveStage(String taskId)
    {
        return stages.findFirstByTaskIdAndStateInOrderByOpenedAtMsDesc(taskId, ACTIVE_STATES)
                .map(SqliteStageStore::toStage);
    }

    @Override
    public List<StageEvent> findEventsByStage(UUID stageId)
    {
        return events.findByStageIdOrderByEventAtMsAsc(stageId.toString()).stream()
                .map(SqliteStageStore::toEvent)
                .toList();
    }

    @Override
    public List<StageEvent> findRecentEventsByStage(UUID stageId, int limit)
    {
        return events.findByStageIdOrderByEventAtMsDesc(stageId.toString(), firstPage(limit)).stream()
                .map(SqliteStageStore::toEvent)
                .toList();
    }

    @Override
    public List<StageEvent> findEventsByTask(String taskId)
    {
        return events.findByTaskIdOrderByEventAtMsAsc(taskId).stream()
                .map(SqliteStageStore::toEvent)
                .toList();
    }

    @Override
    @Transactional
    public ReviewComment saveReviewComment(ReviewComment comment)
    {
        requireNonNull(comment, "comment is null");
        UUID id = comment.id() == null ? UUID.randomUUID() : comment.id();
        ReviewCommentEntity entity = comments.findById(id.toString()).orElseGet(ReviewCommentEntity::new);
        entity.setId(id.toString());
        entity.setTaskId(comment.taskId());
        entity.setFile(comment.file());
        entity.setLine(comment.line());
        entity.setBody(comment.body());
        entity.setCreatedAtMs(comment.createdAt().toEpochMilli());
        entity.setSource(comment.source().name());
        entity.setRemoteLink(comment.remoteLink());
        entity.setResolved(comment.resolved());
        comments.save(entity);
        return toComment(entity);
    }

    @Override
    public Optional<ReviewComment> findReviewCommentById(UUID id)
    {
        return comments.findById(id.toString()).map(SqliteStageStore::toComment);
    }

    @Override
    public boolean reviewCommentExistsByRemoteLink(String remoteLink)
    {
        return remoteLink != null && comments.existsByRemoteLink(remoteLink);
    }

    @Override
    public List<ReviewComment> findUnresolvedComments(String taskId)
    {
        return comments.findByTaskIdAndResolvedFalse(taskId).stream()
                .map(SqliteStageStore::toComment)
                .toList();
    }

    @Override
    public List<ReviewComment> findCommentsBySource(String taskId, ReviewCommentSource source)
    {
        return comments.findByTaskIdAndSource(taskId, source.name()).stream()
                .map(SqliteStageStore::toComment)
                .toList();
    }

    @Override
    @Transactional
    public StageEvent recordEvent(UUID stageId, String taskId, StageEventType type, Map<String, Object> payload)
    {
        return writeEvent(stageId.toString(), taskId, type, payload, Instant.now());
    }

    @Override
    public Optional<StageEvent> findEventById(UUID eventId)
    {
        return events.findById(eventId.toString()).map(SqliteStageStore::toEvent);
    }

    @Override
    @Transactional
    public void updateEventPayload(UUID eventId, Map<String, Object> payload)
    {
        events.findById(eventId.toString()).ifPresent(ev -> {
            ev.setPayloadJson(serialise(payload));
            events.save(ev);
        });
    }

    private StageEvent writeEvent(
            String stageId, String taskId, StageEventType type, Map<String, Object> payload, Instant at)
    {
        TaskStageEventEntity ev = new TaskStageEventEntity();
        ev.setId(UUID.randomUUID().toString());
        ev.setStageId(stageId);
        ev.setTaskId(taskId);
        ev.setEventType(type.name());
        ev.setEventAtMs(at.toEpochMilli());
        ev.setPayloadJson(serialise(payload));
        events.save(ev);
        return toEvent(ev);
    }

    private String serialise(Map<String, Object> payload)
    {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException e) {
            // A Map<String,Object> of plain scalars never fails to serialise;
            // treat an impossible failure as an empty payload rather than
            // letting it break a stage lifecycle write.
            return "{}";
        }
    }

    private static StageInstance toStage(TaskStageEntity e)
    {
        return new StageInstance(
                UUID.fromString(e.getId()),
                e.getTaskId(),
                StageType.valueOf(e.getStageType()),
                StageState.valueOf(e.getState()),
                Instant.ofEpochMilli(e.getOpenedAtMs()),
                Timestamps.instant(e.getClosedAtMs()),
                e.getCallerStageId() == null ? null : UUID.fromString(e.getCallerStageId()));
    }

    private static StageEvent toEvent(TaskStageEventEntity e)
    {
        return new StageEvent(
                UUID.fromString(e.getId()),
                UUID.fromString(e.getStageId()),
                e.getTaskId(),
                StageEventType.valueOf(e.getEventType()),
                Instant.ofEpochMilli(e.getEventAtMs()),
                e.getPayloadJson());
    }

    private static ReviewComment toComment(ReviewCommentEntity e)
    {
        return new ReviewComment(
                UUID.fromString(e.getId()),
                e.getTaskId(),
                e.getFile(),
                e.getLine(),
                e.getBody(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                ReviewCommentSource.valueOf(e.getSource()),
                e.getRemoteLink(),
                e.isResolved());
    }
}
