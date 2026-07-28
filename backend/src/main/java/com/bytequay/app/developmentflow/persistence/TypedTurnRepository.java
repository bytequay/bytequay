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
package com.bytequay.app.developmentflow.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Additive V2 persistence only. Production routing remains on the legacy
 * stores until the owning development-flow slice switches it explicitly.
 */
@Repository
public class TypedTurnRepository
{
    private final JdbcTemplate jdbc;

    public TypedTurnRepository(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public void insert(ThreadTurn turn)
    {
        requireNonNull(turn, "turn is null");
        TurnData data = turn.data();
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms, started_at_ms,
                    finished_at_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.id(), turn.trunkId(), data.purpose(), data.status().name(),
                data.operationId(), data.attempt(), data.deliveryLane(), data.launchInput(),
                millis(data.requestedAt()), millis(data.startedAt()), millis(data.finishedAt()),
                data.errorMessage());
    }

    public void insert(TaskTurn turn)
    {
        requireNonNull(turn, "turn is null");
        TurnData data = turn.data();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt, task_epoch,
                    trigger_stage_id, trigger_stage_generation, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.id(), turn.taskId(), data.purpose(), data.status().name(),
                data.operationId(), data.attempt(), turn.taskEpoch(), turn.triggerStageId(),
                turn.triggerStageGeneration(), turn.expectedCodeFingerprint(),
                turn.expectedHeadSha(), turn.expectedBaseSha(), data.deliveryLane(),
                data.launchInput(), millis(data.requestedAt()), millis(data.startedAt()),
                millis(data.finishedAt()), data.errorMessage());
    }

    public void insert(StageTurn turn)
    {
        requireNonNull(turn, "turn is null");
        TurnData data = turn.data();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.id(), turn.stageId(), turn.stageGeneration(), data.purpose(),
                data.status().name(), data.operationId(), data.attempt(), turn.taskEpoch(),
                turn.expectedCodeFingerprint(), turn.expectedHeadSha(), turn.expectedBaseSha(),
                data.deliveryLane(), data.launchInput(), millis(data.requestedAt()),
                millis(data.startedAt()), millis(data.finishedAt()), data.errorMessage());
    }

    public void insert(ReviewAssignmentTurn turn)
    {
        requireNonNull(turn, "turn is null");
        TurnData data = turn.data();
        jdbc.update("""
                INSERT INTO review_assignment_turn(
                    id, assignment_id, purpose, status, operation_id, attempt,
                    start_commit, delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.id(), turn.assignmentId(), data.purpose(), data.status().name(),
                data.operationId(), data.attempt(), turn.startCommit(), data.deliveryLane(),
                data.launchInput(), millis(data.requestedAt()), millis(data.startedAt()),
                millis(data.finishedAt()), data.errorMessage());
    }

    public Optional<ThreadTurn> find(ThreadTurnId id)
    {
        requireNonNull(id, "id is null");
        return one(jdbc.query("""
                SELECT id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms, started_at_ms,
                    finished_at_ms, error_message
                FROM thread_turn WHERE id = ?
                """,
                (rs, row) -> new ThreadTurn(rs.getString("trunk_id"), data(rs)),
                id.value()));
    }

    public Optional<TaskTurn> find(TaskTurnId id)
    {
        requireNonNull(id, "id is null");
        return one(jdbc.query("""
                SELECT id, task_id, purpose, status, operation_id, attempt, task_epoch,
                    trigger_stage_id, trigger_stage_generation, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms, error_message
                FROM task_turn WHERE id = ?
                """,
                (rs, row) -> new TaskTurn(
                        rs.getString("task_id"), rs.getInt("task_epoch"),
                        rs.getString("trigger_stage_id"), integer(rs, "trigger_stage_generation"),
                        rs.getString("expected_code_fingerprint"), rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"), data(rs)),
                id.value()));
    }

    public Optional<StageTurn> find(StageTurnId id)
    {
        requireNonNull(id, "id is null");
        return one(jdbc.query("""
                SELECT id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message
                FROM stage_turn WHERE id = ?
                """,
                (rs, row) -> new StageTurn(
                        rs.getString("stage_id"), rs.getInt("stage_generation"),
                        rs.getInt("task_epoch"), rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"), rs.getString("expected_base_sha"),
                        data(rs)),
                id.value()));
    }

    public Optional<ReviewAssignmentTurn> find(ReviewAssignmentTurnId id)
    {
        requireNonNull(id, "id is null");
        return one(jdbc.query("""
                SELECT id, assignment_id, purpose, status, operation_id, attempt,
                    start_commit, delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message
                FROM review_assignment_turn WHERE id = ?
                """,
                (rs, row) -> new ReviewAssignmentTurn(
                        rs.getString("assignment_id"), rs.getString("start_commit"), data(rs)),
                id.value()));
    }

    /**
     * Atomically advances delivery state only if the caller still owns the
     * expected state. A false result is a stale or duplicate command.
     */
    public boolean transition(
            TurnId id, TurnStatus expected, TurnStatus next, Instant at, String errorMessage)
    {
        requireNonNull(id, "id is null");
        requireNonNull(expected, "expected is null");
        requireNonNull(next, "next is null");
        requireNonNull(at, "at is null");
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("illegal Turn transition: " + expected + " -> " + next);
        }
        Table table = table(id);
        int updated;
        if (next == TurnStatus.RUNNING) {
            updated = jdbc.update("""
                    UPDATE %s
                    SET status = ?, started_at_ms = ?, error_message = ?
                    WHERE id = ? AND status = ?
                        AND started_at_ms IS NULL AND finished_at_ms IS NULL
                        AND requested_at_ms <= ?
                    """.formatted(table.turnTable),
                    next.name(), at.toEpochMilli(), errorMessage, id.value(), expected.name(),
                    at.toEpochMilli());
        }
        else if (next.terminal()) {
            updated = jdbc.update("""
                    UPDATE %s
                    SET status = ?, finished_at_ms = ?, error_message = ?
                    WHERE id = ? AND status = ? AND finished_at_ms IS NULL
                        AND %s
                        AND requested_at_ms <= ?
                        AND (started_at_ms IS NULL OR started_at_ms <= ?)
                    """.formatted(
                            table.turnTable,
                            expected == TurnStatus.RUNNING
                                    ? "started_at_ms IS NOT NULL"
                                    : "started_at_ms IS NULL"),
                    next.name(), at.toEpochMilli(), errorMessage, id.value(), expected.name(),
                    at.toEpochMilli(), at.toEpochMilli());
        }
        else {
            updated = jdbc.update("""
                    UPDATE %s SET status = ?, error_message = ?
                    WHERE id = ? AND status = ?
                        AND started_at_ms IS NULL AND finished_at_ms IS NULL
                    """.formatted(table.turnTable),
                    next.name(), errorMessage, id.value(), expected.name());
        }
        return updated == 1;
    }

    public <T extends TurnId> void insert(Message<T> message)
    {
        requireNonNull(message, "message is null");
        jdbc.update("""
                INSERT INTO %s_message(id, turn_id, seq, role, body, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(table(message.turnId()).supportPrefix),
                message.id(), message.turnId().value(), message.seq(), message.role(),
                message.body(), message.createdAt().toEpochMilli());
    }

    public <T extends TurnId> List<Message<T>> listMessages(T turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return jdbc.query("""
                SELECT id, seq, role, body, created_at_ms
                FROM %s_message WHERE turn_id = ? ORDER BY seq
                """.formatted(table(turnId).supportPrefix),
                (rs, row) -> new Message<>(
                        rs.getString("id"), turnId, rs.getInt("seq"), rs.getString("role"),
                        rs.getString("body"), instant(rs, "created_at_ms")),
                turnId.value());
    }

    /** Answers or revises one exact question. */
    public boolean answer(
            TurnId turnId,
            String callId,
            QuestionState expectedState,
            int expectedRevision,
            String answer,
            Instant answeredAt)
    {
        requireNonNull(turnId, "turnId is null");
        required(callId, "callId");
        requireNonNull(expectedState, "expectedState is null");
        required(answer, "answer");
        requireNonNull(answeredAt, "answeredAt is null");
        if ((expectedState == QuestionState.OPEN && expectedRevision != 0)
                || (expectedState == QuestionState.ANSWERED && expectedRevision < 1)
                || expectedState == QuestionState.CANCELED) {
            throw new IllegalArgumentException("invalid expected question state/revision");
        }
        return jdbc.update("""
                UPDATE %s_question
                SET state = 'ANSWERED', answer = ?, answer_revision = ?, answered_at_ms = ?
                WHERE turn_id = ? AND call_id = ? AND state = ? AND answer_revision = ?
                    AND created_at_ms <= ?
                    AND (answered_at_ms IS NULL OR answered_at_ms <= ?)
                    AND ((state = 'OPEN' AND answer IS NULL AND answered_at_ms IS NULL)
                        OR (state = 'ANSWERED' AND answer IS NOT NULL AND answered_at_ms IS NOT NULL))
                """.formatted(table(turnId).supportPrefix),
                answer, expectedRevision + 1, answeredAt.toEpochMilli(), turnId.value(), callId,
                expectedState.name(), expectedRevision, answeredAt.toEpochMilli(),
                answeredAt.toEpochMilli()) == 1;
    }

    public <T extends TurnId> void insert(Question<T> question)
    {
        requireNonNull(question, "question is null");
        jdbc.update("""
                INSERT INTO %s_question(
                    id, turn_id, call_id, prompt, state, answer, answer_revision,
                    created_at_ms, answered_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(table(question.turnId()).supportPrefix),
                question.id(), question.turnId().value(), question.callId(), question.prompt(),
                question.state().name(), question.answer(), question.answerRevision(),
                question.createdAt().toEpochMilli(), millis(question.answeredAt()));
    }

    public <T extends TurnId> List<Question<T>> listQuestions(T turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return jdbc.query("""
                SELECT id, call_id, prompt, state, answer, answer_revision,
                    created_at_ms, answered_at_ms
                FROM %s_question WHERE turn_id = ? ORDER BY created_at_ms, id
                """.formatted(table(turnId).supportPrefix),
                (rs, row) -> new Question<>(
                        rs.getString("id"), turnId, rs.getString("call_id"),
                        rs.getString("prompt"), QuestionState.valueOf(rs.getString("state")),
                        rs.getString("answer"), rs.getInt("answer_revision"),
                        instant(rs, "created_at_ms"), instant(rs, "answered_at_ms")),
                turnId.value());
    }

    public <T extends TurnId> void insert(Attachment<T> attachment)
    {
        requireNonNull(attachment, "attachment is null");
        jdbc.update("""
                INSERT INTO %s_attachment(
                    id, turn_id, kind, content_ref, media_type, digest, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(table(attachment.turnId()).supportPrefix),
                attachment.id(), attachment.turnId().value(), attachment.kind(),
                attachment.contentRef(), attachment.mediaType(), attachment.digest(),
                attachment.createdAt().toEpochMilli());
    }

    public <T extends TurnId> List<Attachment<T>> listAttachments(T turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return jdbc.query("""
                SELECT id, kind, content_ref, media_type, digest, created_at_ms
                FROM %s_attachment WHERE turn_id = ? ORDER BY created_at_ms, id
                """.formatted(table(turnId).supportPrefix),
                (rs, row) -> new Attachment<>(
                        rs.getString("id"), turnId, rs.getString("kind"),
                        rs.getString("content_ref"), rs.getString("media_type"),
                        rs.getString("digest"), instant(rs, "created_at_ms")),
                turnId.value());
    }

    public <T extends TurnId> void insert(Checkpoint<T> checkpoint)
    {
        requireNonNull(checkpoint, "checkpoint is null");
        jdbc.update("""
                INSERT INTO %s_checkpoint(id, turn_id, seq, payload, created_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """.formatted(table(checkpoint.turnId()).supportPrefix),
                checkpoint.id(), checkpoint.turnId().value(), checkpoint.seq(),
                checkpoint.payload(), checkpoint.createdAt().toEpochMilli());
    }

    public <T extends TurnId> List<Checkpoint<T>> listCheckpoints(T turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return jdbc.query("""
                SELECT id, seq, payload, created_at_ms
                FROM %s_checkpoint WHERE turn_id = ? ORDER BY seq
                """.formatted(table(turnId).supportPrefix),
                (rs, row) -> new Checkpoint<>(
                        rs.getString("id"), turnId, rs.getInt("seq"),
                        rs.getString("payload"), instant(rs, "created_at_ms")),
                turnId.value());
    }

    private static TurnData data(ResultSet rs)
            throws SQLException
    {
        return new TurnData(
                rs.getString("id"), rs.getString("purpose"),
                TurnStatus.valueOf(rs.getString("status")), rs.getString("operation_id"),
                rs.getInt("attempt"), rs.getString("delivery_lane"),
                rs.getString("launch_input"), instant(rs, "requested_at_ms"),
                instant(rs, "started_at_ms"), instant(rs, "finished_at_ms"),
                rs.getString("error_message"));
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Integer integer(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long millis(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static <T> Optional<T> one(List<T> rows)
    {
        return rows.stream().findFirst();
    }

    private static Table table(TurnId id)
    {
        if (id instanceof ThreadTurnId) {
            return Table.THREAD;
        }
        if (id instanceof TaskTurnId) {
            return Table.TASK;
        }
        if (id instanceof StageTurnId) {
            return Table.STAGE;
        }
        if (id instanceof ReviewAssignmentTurnId) {
            return Table.REVIEW_ASSIGNMENT;
        }
        throw new IllegalArgumentException("unknown Turn id type: " + id.getClass().getName());
    }

    private static String required(String value, String name)
    {
        return requireNonNull(value, name + " is null");
    }

    private enum Table
    {
        THREAD("thread_turn", "thread"),
        TASK("task_turn", "task"),
        STAGE("stage_turn", "stage"),
        REVIEW_ASSIGNMENT("review_assignment_turn", "review_assignment");

        private final String turnTable;
        private final String supportPrefix;

        Table(String turnTable, String supportPrefix)
        {
            this.turnTable = turnTable;
            this.supportPrefix = supportPrefix;
        }
    }

    public enum TurnStatus
    {
        REQUESTED,
        QUEUED,
        CLAIMED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELED,
        SUPERSEDED;

        private boolean terminal()
        {
            return this == SUCCEEDED || this == FAILED || this == CANCELED || this == SUPERSEDED;
        }

        private boolean canTransitionTo(TurnStatus next)
        {
            if (terminal()) {
                return false;
            }
            if (next == CANCELED || next == SUPERSEDED) {
                return true;
            }
            return switch (this) {
                case REQUESTED -> next == QUEUED;
                case QUEUED -> next == CLAIMED;
                case CLAIMED -> next == RUNNING;
                case RUNNING -> next == SUCCEEDED || next == FAILED;
                case SUCCEEDED, FAILED, CANCELED, SUPERSEDED -> false;
            };
        }
    }

    public enum QuestionState
    {
        OPEN,
        ANSWERED,
        CANCELED
    }

    public sealed interface TurnId
            permits ThreadTurnId, TaskTurnId, StageTurnId, ReviewAssignmentTurnId
    {
        String value();
    }

    public record ThreadTurnId(String value)
            implements TurnId
    {
        public ThreadTurnId
        {
            required(value, "value");
        }
    }

    public record TaskTurnId(String value)
            implements TurnId
    {
        public TaskTurnId
        {
            required(value, "value");
        }
    }

    public record StageTurnId(String value)
            implements TurnId
    {
        public StageTurnId
        {
            required(value, "value");
        }
    }

    public record ReviewAssignmentTurnId(String value)
            implements TurnId
    {
        public ReviewAssignmentTurnId
        {
            required(value, "value");
        }
    }

    public record TurnData(
            String id,
            String purpose,
            TurnStatus status,
            String operationId,
            int attempt,
            String deliveryLane,
            String launchInput,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage)
    {
        public TurnData
        {
            required(id, "id");
            required(purpose, "purpose");
            requireNonNull(status, "status is null");
            required(operationId, "operationId");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            required(deliveryLane, "deliveryLane");
            required(launchInput, "launchInput");
            requireNonNull(requestedAt, "requestedAt is null");
            switch (status) {
                case REQUESTED, QUEUED, CLAIMED -> {
                    if (startedAt != null || finishedAt != null) {
                        throw new IllegalArgumentException(status + " Turn cannot have execution timestamps");
                    }
                }
                case RUNNING -> {
                    if (startedAt == null || finishedAt != null) {
                        throw new IllegalArgumentException("RUNNING Turn requires only startedAt");
                    }
                }
                case SUCCEEDED, FAILED -> {
                    if (startedAt == null || finishedAt == null) {
                        throw new IllegalArgumentException(status + " Turn requires start and finish timestamps");
                    }
                }
                case CANCELED, SUPERSEDED -> {
                    if (finishedAt == null) {
                        throw new IllegalArgumentException(status + " Turn requires finishedAt");
                    }
                }
            }
            if (startedAt != null && startedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("startedAt is before requestedAt");
            }
            if (finishedAt != null && finishedAt.isBefore(
                    startedAt == null ? requestedAt : startedAt)) {
                throw new IllegalArgumentException("finishedAt is before Turn start");
            }
        }
    }

    public record ThreadTurn(String trunkId, TurnData data)
    {
        public ThreadTurn
        {
            required(trunkId, "trunkId");
            requireNonNull(data, "data is null");
        }
    }

    public record TaskTurn(
            String taskId,
            int taskEpoch,
            String triggerStageId,
            Integer triggerStageGeneration,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            TurnData data)
    {
        public TaskTurn
        {
            required(taskId, "taskId");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            if ((triggerStageId == null) != (triggerStageGeneration == null)) {
                throw new IllegalArgumentException("trigger Stage id and generation must be both present or absent");
            }
            if (triggerStageGeneration != null && triggerStageGeneration < 1) {
                throw new IllegalArgumentException("triggerStageGeneration must be positive");
            }
            requireNonNull(data, "data is null");
        }
    }

    public record StageTurn(
            String stageId,
            int stageGeneration,
            int taskEpoch,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            TurnData data)
    {
        public StageTurn
        {
            required(stageId, "stageId");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            requireNonNull(data, "data is null");
        }
    }

    public record ReviewAssignmentTurn(String assignmentId, String startCommit, TurnData data)
    {
        public ReviewAssignmentTurn
        {
            required(assignmentId, "assignmentId");
            required(startCommit, "startCommit");
            requireNonNull(data, "data is null");
        }
    }

    public record Message<T extends TurnId>(
            String id, T turnId, int seq, String role, String body, Instant createdAt)
    {
        public Message
        {
            required(id, "id");
            requireNonNull(turnId, "turnId is null");
            if (seq < 1) {
                throw new IllegalArgumentException("seq must be positive");
            }
            required(role, "role");
            required(body, "body");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record Question<T extends TurnId>(
            String id,
            T turnId,
            String callId,
            String prompt,
            QuestionState state,
            String answer,
            int answerRevision,
            Instant createdAt,
            Instant answeredAt)
    {
        public Question
        {
            required(id, "id");
            requireNonNull(turnId, "turnId is null");
            required(callId, "callId");
            required(prompt, "prompt");
            requireNonNull(state, "state is null");
            if (answerRevision < 0) {
                throw new IllegalArgumentException("answerRevision must not be negative");
            }
            requireNonNull(createdAt, "createdAt is null");
            switch (state) {
                case OPEN -> {
                    if (answer != null || answerRevision != 0 || answeredAt != null) {
                        throw new IllegalArgumentException("OPEN question cannot have an answer");
                    }
                }
                case ANSWERED -> {
                    if (answer == null || answerRevision < 1 || answeredAt == null) {
                        throw new IllegalArgumentException("ANSWERED question requires answer evidence");
                    }
                }
                case CANCELED -> {
                    if (answer != null || answerRevision != 0 || answeredAt == null) {
                        throw new IllegalArgumentException("CANCELED question has invalid answer evidence");
                    }
                }
            }
            if (answeredAt != null && answeredAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("answeredAt is before createdAt");
            }
        }
    }

    public record Attachment<T extends TurnId>(
            String id,
            T turnId,
            String kind,
            String contentRef,
            String mediaType,
            String digest,
            Instant createdAt)
    {
        public Attachment
        {
            required(id, "id");
            requireNonNull(turnId, "turnId is null");
            required(kind, "kind");
            required(contentRef, "contentRef");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record Checkpoint<T extends TurnId>(
            String id, T turnId, int seq, String payload, Instant createdAt)
    {
        public Checkpoint
        {
            required(id, "id");
            requireNonNull(turnId, "turnId is null");
            if (seq < 1) {
                throw new IllegalArgumentException("seq must be positive");
            }
            required(payload, "payload");
            requireNonNull(createdAt, "createdAt is null");
        }
    }
}
