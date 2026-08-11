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
package com.bytequay.app.flow.timeline;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** A pure, snapshot-consistent projection over retained greenfield PR facts. */
public final class PrTimelineProjection
{
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String PAGE_SQL = """
            WITH pr_owner AS (
                SELECT pr_id, task_id, remote_identity_id
                FROM flow_runtime_pr
                WHERE pr_id = ?
            ),
            events AS (
                SELECT
                    'task-lifecycle:' || l.lifecycle_revision_id || ':1'
                        AS event_id,
                    l.recorded_at,
                    NULL AS occurred_at,
                    10 AS type_rank,
                    'LOCAL' AS source,
                    'TASK_LIFECYCLE' AS kind,
                    'PROGRAM' AS actor,
                    CASE l.to_status
                        WHEN 'CREATED' THEN 'TASK_CREATED'
                        WHEN 'ACTIVE' THEN 'TASK_ACTIVE'
                        WHEN 'WAITING_USER' THEN 'TASK_WAITING_USER'
                        WHEN 'NEEDS_ATTENTION' THEN 'TASK_NEEDS_ATTENTION'
                        WHEN 'COMPLETED' THEN 'TASK_COMPLETED'
                        WHEN 'CANCELED' THEN 'TASK_CANCELED'
                    END AS status,
                    NULL AS head_sha,
                    'TASK_LIFECYCLE_REVISION' AS owner_type,
                    l.lifecycle_revision_id AS owner_id,
                    1 AS owner_revision
                FROM pr_owner p
                JOIN flow_runtime_task_lifecycle_revision l
                    ON l.task_id = p.task_id

                UNION ALL
                SELECT
                    'task-base:' || b.base_revision_id || ':1',
                    b.recorded_at, NULL, 20, 'LOCAL', 'TASK_BASE_REVISION',
                    'PROGRAM', 'BASE_RECORDED', NULL,
                    'TASK_BASE_REVISION', b.base_revision_id, 1
                FROM pr_owner p
                JOIN flow_runtime_task_base_revision b
                    ON b.task_id = p.task_id

                UNION ALL
                SELECT
                    'change-set:' || c.change_set_revision_id || ':1',
                    c.adopted_at, NULL, 30, 'LOCAL', 'CHANGE_SET_REVISION',
                    CASE c.source
                        WHEN 'TASK_AGENT' THEN 'TASK_AGENT'
                        WHEN 'CI_FIXER' THEN 'CI_FIXER'
                        ELSE 'PROGRAM'
                    END,
                    'CHANGE_SET_ADOPTED', c.head_sha,
                    'CHANGE_SET_REVISION', c.change_set_revision_id, 1
                FROM pr_owner p
                JOIN flow_runtime_change_set_revision c
                    ON c.task_id = p.task_id

                UNION ALL
                SELECT
                    'pr:' || p.pr_id || ':1',
                    pr.created_at, NULL, 40, 'LOCAL', 'PR_MATERIALIZED',
                    'PROGRAM', 'PR_READY_LOCAL', pr.created_from_head_sha,
                    'PULL_REQUEST', pr.pr_id, 1
                FROM pr_owner p
                JOIN flow_runtime_pr pr ON pr.pr_id = p.pr_id

                UNION ALL
                SELECT
                    'remote-identity:' || r.remote_identity_id || ':1',
                    r.bound_at, NULL, 50, 'GITHUB', 'REMOTE_IDENTITY_BOUND',
                    'PROGRAM', 'REMOTE_IDENTITY_BOUND', NULL,
                    'REMOTE_IDENTITY', r.remote_identity_id, 1
                FROM pr_owner p
                JOIN flow_runtime_remote_identity r
                    ON r.remote_identity_id = p.remote_identity_id

                UNION ALL
                SELECT
                    'check:' || c.check_run_id || ':1',
                    c.completed_at, NULL, 60, 'LOCAL',
                    'LOCAL_CHECK_COMPLETED', 'PROGRAM',
                    CASE c.conclusion
                        WHEN 'PASSED' THEN 'LOCAL_CHECK_PASSED'
                        WHEN 'FAILED' THEN 'LOCAL_CHECK_FAILED'
                        WHEN 'UNAVAILABLE' THEN 'LOCAL_CHECK_UNAVAILABLE'
                    END,
                    c.observed_start_head,
                    'LOCAL_CHECK_RUN', c.check_run_id, 1
                FROM pr_owner p
                JOIN flow_runtime_local_check_run c ON c.task_id = p.task_id

                UNION ALL
                SELECT
                    'agent-result:' || x.result_id || ':1',
                    x.stored_at, NULL, 70, 'LOCAL', 'AGENT_RESULT_STORED',
                    CASE r.role
                        WHEN 'TASK_AGENT' THEN 'TASK_AGENT'
                        WHEN 'ADVERSARIAL_REVIEWER'
                            THEN 'ADVERSARIAL_REVIEWER'
                        WHEN 'CI_FIXER' THEN 'CI_FIXER'
                        WHEN 'CI_LEARNER' THEN 'CI_LEARNER'
                    END,
                    CASE x.terminal_outcome
                        WHEN 'COMPLETED' THEN 'AGENT_COMPLETED'
                        WHEN 'FAILED' THEN 'AGENT_FAILED'
                        WHEN 'CANCELED' THEN 'AGENT_CANCELED'
                    END,
                    r.head_sha, 'AGENT_RESULT', x.result_id, 1
                FROM pr_owner p
                JOIN flow_runtime_agent_session s ON s.task_id = p.task_id
                JOIN flow_runtime_agent_run r ON r.session_id = s.session_id
                JOIN flow_runtime_agent_result x ON x.run_id = r.run_id
                WHERE r.role IN (
                    'TASK_AGENT', 'ADVERSARIAL_REVIEWER',
                    'CI_FIXER', 'CI_LEARNER'
                )

                UNION ALL
                SELECT
                    'ci-consent:' || c.consent_id || ':' || c.revision,
                    c.recorded_at, NULL, 80, 'LOCAL', 'CI_CONSENT_REVISION',
                    'LOCAL_USER',
                    CASE c.enabled
                        WHEN 1 THEN 'CONSENT_ENABLED'
                        ELSE 'CONSENT_REVOKED'
                    END,
                    NULL, 'CI_CONSENT_REVISION', c.consent_id, c.revision
                FROM pr_owner p
                JOIN flow_user_gate_ci_consent_revision c
                    ON c.pr_id = p.pr_id

                UNION ALL
                SELECT
                    'gate:' || t.gate_id || ':' || t.sequence,
                    t.recorded_at, NULL, 90, 'LOCAL', 'GATE_TRANSITION',
                    CASE t.actor_type
                        WHEN 'USER' THEN 'LOCAL_USER'
                        ELSE CASE t.actor_id
                            WHEN 'USER_GATES_CI_CONSENT'
                                THEN 'CI_UPDATE_CONSENT'
                            ELSE 'PROGRAM'
                        END
                    END,
                    CASE t.to_state
                        WHEN 'OPEN' THEN 'GATE_OPEN'
                        WHEN 'AUTHORIZED' THEN 'GATE_AUTHORIZED'
                        WHEN 'EXECUTING' THEN 'GATE_EXECUTING'
                        WHEN 'NEEDS_ATTENTION'
                            THEN 'GATE_NEEDS_ATTENTION'
                        WHEN 'CONSUMED' THEN 'GATE_CONSUMED'
                        WHEN 'STALE' THEN 'GATE_STALE'
                    END,
                    COALESCE(s.proposed_head, i.proposed_head),
                    'GATE_TRANSITION', t.gate_id,
                    t.sequence
                FROM pr_owner p
                JOIN flow_user_gate g ON g.pr_id = p.pr_id
                JOIN flow_user_gate_transition t ON t.gate_id = g.gate_id
                JOIN flow_user_gate_revision r
                    ON r.gate_id = t.gate_id
                    AND r.revision = t.gate_revision
                LEFT JOIN flow_user_gate_subject s
                    ON s.subject_id = r.subject_manifest_ref
                LEFT JOIN flow_user_gate_initial_publish_subject i
                    ON i.subject_id = r.subject_manifest_ref
                WHERE s.subject_id IS NOT NULL OR i.subject_id IS NOT NULL

                UNION ALL
                SELECT
                    'effect-receipt:' || x.receipt_id || ':1',
                    x.recorded_at, NULL, 100, 'GITHUB',
                    'EXTERNAL_EFFECT_RECEIPT', 'PROGRAM', 'EFFECT_APPLIED',
                    x.proposed_head, 'EXTERNAL_EFFECT_RECEIPT', x.receipt_id, 1
                FROM pr_owner p
                JOIN flow_github_effect_plan_envelope e ON e.pr_id = p.pr_id
                JOIN flow_github_effect_receipt_envelope x
                    ON x.plan_id = e.plan_id

                UNION ALL
                SELECT
                    'ci-check:' || c.observation_id || ':1',
                    c.observed_at, c.completed_at, 110, 'GITHUB',
                    'CI_CHECK_OBSERVED', 'GITHUB',
                    CASE
                        WHEN upper(c.status) IN (
                            'QUEUED', 'PENDING', 'REQUESTED',
                            'WAITING', 'IN_PROGRESS'
                        ) THEN 'CI_PENDING'
                        WHEN upper(c.status) = 'COMPLETED'
                            AND upper(COALESCE(c.conclusion, '')) = 'SUCCESS'
                            THEN 'CI_SUCCESS'
                        WHEN upper(c.status) = 'COMPLETED'
                            AND upper(COALESCE(c.conclusion, '')) = 'FAILURE'
                            THEN 'CI_FAILURE'
                        WHEN upper(c.status) = 'COMPLETED'
                            THEN 'CI_TERMINAL_OTHER'
                        ELSE 'CI_UNKNOWN'
                    END,
                    c.head_sha, 'CI_CHECK_OBSERVATION', c.observation_id, 1
                FROM pr_owner p
                JOIN flow_ci_check_observation c ON c.pr_id = p.pr_id

                UNION ALL
                SELECT
                    'ci-lesson:' || l.lesson_id || ':1',
                    l.created_at, NULL, 120, 'LOCAL', 'CI_LESSON_CANDIDATE',
                    'CI_LEARNER', 'LESSON_CANDIDATE', s.published_head,
                    'CI_LESSON', l.lesson_id, 1
                FROM pr_owner p
                JOIN flow_ci_learning_subject s ON s.pr_id = p.pr_id
                JOIN flow_ci_lesson l ON l.subject_id = s.subject_id
            ),
            meta AS (
                SELECT
                    COUNT(*) AS event_count,
                    CASE WHEN ? = 0 THEN 1 ELSE COALESCE(MAX(CASE
                        WHEN recorded_at = ?
                            AND type_rank = ?
                            AND event_id = ? COLLATE BINARY
                        THEN 1 ELSE 0 END), 0) END AS cursor_match
                FROM events
            ),
            page_rows AS (
                SELECT *
                FROM events
                WHERE ? = 0
                    OR recorded_at > ?
                    OR (recorded_at = ? AND type_rank > ?)
                    OR (recorded_at = ? AND type_rank = ?
                        AND event_id COLLATE BINARY > ? COLLATE BINARY)
                ORDER BY recorded_at, type_rank, event_id COLLATE BINARY
                LIMIT ?
            )
            SELECT m.event_count, m.cursor_match,
                   p.event_id, p.recorded_at, p.occurred_at, p.type_rank,
                   p.source, p.kind, p.actor, p.status, p.head_sha,
                   p.owner_type, p.owner_id, p.owner_revision
            FROM meta m
            LEFT JOIN page_rows p ON 1 = 1
            ORDER BY p.recorded_at, p.type_rank, p.event_id COLLATE BINARY
            """;

    private final JdbcTemplate jdbc;

    public PrTimelineProjection(DataSource dataSource)
    {
        jdbc = new JdbcTemplate(requireNonNull(dataSource, "dataSource is null"));
    }

    public TimelinePage page(String prId, TimelineCursor after, int limit)
    {
        requireText(prId, "prId");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        long cursorTime = after == null ? Long.MIN_VALUE
                : after.recordedAt().toEpochMilli();
        int cursorRank = after == null ? -1 : after.typeRank();
        String cursorEvent = after == null ? "" : after.eventId();
        int cursorPresent = after == null ? 0 : 1;

        Snapshot snapshot = jdbc.query(PAGE_SQL, statement -> {
            int index = 1;
            statement.setString(index++, prId);
            statement.setInt(index++, cursorPresent);
            statement.setLong(index++, cursorTime);
            statement.setInt(index++, cursorRank);
            statement.setString(index++, cursorEvent);
            statement.setInt(index++, cursorPresent);
            statement.setLong(index++, cursorTime);
            statement.setLong(index++, cursorTime);
            statement.setInt(index++, cursorRank);
            statement.setLong(index++, cursorTime);
            statement.setInt(index++, cursorRank);
            statement.setString(index++, cursorEvent);
            statement.setInt(index, limit + 1);
        }, PrTimelineProjection::readSnapshot);

        if (snapshot.eventCount() == 0) {
            throw new IllegalArgumentException("Unknown greenfield PR: " + prId);
        }
        if (after != null && (!after.prId().equals(prId)
                || after.schemaVersion() != SCHEMA_VERSION
                || after.eventCount() != snapshot.eventCount()
                || !snapshot.cursorMatch())) {
            return new TimelinePage(
                    PageStatus.RESTART_REQUIRED, List.of(), null, false,
                    snapshot.eventCount());
        }

        boolean hasMore = snapshot.events().size() > limit;
        List<TimelineEvent> events = hasMore
                ? List.copyOf(snapshot.events().subList(0, limit))
                : snapshot.events();
        TimelineCursor next = events.isEmpty() ? after : cursor(
                prId, snapshot.eventCount(), events.getLast());
        return new TimelinePage(
                PageStatus.OK, events, next, hasMore, snapshot.eventCount());
    }

    private static Snapshot readSnapshot(ResultSet rows) throws SQLException
    {
        long eventCount = -1;
        boolean cursorMatch = false;
        List<TimelineEvent> events = new ArrayList<>();
        while (rows.next()) {
            eventCount = rows.getLong("event_count");
            cursorMatch = rows.getInt("cursor_match") == 1;
            String eventId = rows.getString("event_id");
            if (eventId == null) {
                continue;
            }
            long occurred = rows.getLong("occurred_at");
            boolean occurredMissing = rows.wasNull();
            events.add(new TimelineEvent(
                    eventId,
                    Instant.ofEpochMilli(rows.getLong("recorded_at")),
                    occurredMissing ? null : Instant.ofEpochMilli(occurred),
                    rows.getInt("type_rank"),
                    EventSource.valueOf(rows.getString("source")),
                    EventKind.valueOf(rows.getString("kind")),
                    EventActor.valueOf(rows.getString("actor")),
                    EventStatus.valueOf(rows.getString("status")),
                    rows.getString("head_sha"),
                    new OwnerRef(
                            OwnerType.valueOf(rows.getString("owner_type")),
                            rows.getString("owner_id"),
                            rows.getLong("owner_revision"))));
        }
        return new Snapshot(eventCount, cursorMatch, List.copyOf(events));
    }

    private static TimelineCursor cursor(
            String prId, long eventCount, TimelineEvent event)
    {
        return new TimelineCursor(
                prId, SCHEMA_VERSION, eventCount, event.recordedAt(),
                event.typeRank(), event.eventId());
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    public enum PageStatus { OK, RESTART_REQUIRED }
    public enum EventSource { LOCAL, GITHUB }
    public enum EventActor {
        PROGRAM, LOCAL_USER, CI_UPDATE_CONSENT,
        TASK_AGENT, ADVERSARIAL_REVIEWER,
        CI_FIXER, CI_LEARNER, GITHUB
    }
    public enum EventKind {
        TASK_LIFECYCLE,
        TASK_BASE_REVISION,
        CHANGE_SET_REVISION,
        PR_MATERIALIZED,
        REMOTE_IDENTITY_BOUND,
        LOCAL_CHECK_COMPLETED,
        AGENT_RESULT_STORED,
        CI_CONSENT_REVISION,
        GATE_TRANSITION,
        EXTERNAL_EFFECT_RECEIPT,
        CI_CHECK_OBSERVED,
        CI_LESSON_CANDIDATE
    }
    public enum EventStatus {
        TASK_CREATED, TASK_ACTIVE, TASK_WAITING_USER, TASK_NEEDS_ATTENTION,
        TASK_COMPLETED, TASK_CANCELED,
        BASE_RECORDED, CHANGE_SET_ADOPTED, PR_READY_LOCAL,
        REMOTE_IDENTITY_BOUND,
        LOCAL_CHECK_PASSED, LOCAL_CHECK_FAILED, LOCAL_CHECK_UNAVAILABLE,
        AGENT_COMPLETED, AGENT_FAILED, AGENT_CANCELED,
        CONSENT_ENABLED, CONSENT_REVOKED,
        GATE_OPEN, GATE_AUTHORIZED, GATE_EXECUTING,
        GATE_NEEDS_ATTENTION, GATE_CONSUMED, GATE_STALE,
        EFFECT_APPLIED,
        CI_PENDING, CI_SUCCESS, CI_FAILURE, CI_TERMINAL_OTHER, CI_UNKNOWN,
        LESSON_CANDIDATE
    }
    public enum OwnerType {
        TASK_LIFECYCLE_REVISION,
        TASK_BASE_REVISION,
        CHANGE_SET_REVISION,
        PULL_REQUEST,
        REMOTE_IDENTITY,
        LOCAL_CHECK_RUN,
        AGENT_RESULT,
        CI_CONSENT_REVISION,
        GATE_TRANSITION,
        EXTERNAL_EFFECT_RECEIPT,
        CI_CHECK_OBSERVATION,
        CI_LESSON
    }

    public record OwnerRef(OwnerType type, String ownerId, long revision)
    {
        public OwnerRef
        {
            requireNonNull(type, "type is null");
            requireText(ownerId, "ownerId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    public record TimelineEvent(
            String eventId,
            Instant recordedAt,
            Instant occurredAt,
            int typeRank,
            EventSource source,
            EventKind kind,
            EventActor actor,
            EventStatus status,
            String headSha,
            OwnerRef ownerRef)
    {
        public TimelineEvent
        {
            requireText(eventId, "eventId");
            requireNonNull(recordedAt, "recordedAt is null");
            requireNonNull(source, "source is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(actor, "actor is null");
            requireNonNull(status, "status is null");
            requireNonNull(ownerRef, "ownerRef is null");
            if (typeRank < 1 || typeRank > 1_000) {
                throw new IllegalArgumentException("typeRank is invalid");
            }
            if (headSha != null) {
                requireText(headSha, "headSha");
            }
        }
    }

    public record TimelineCursor(
            String prId,
            int schemaVersion,
            long eventCount,
            Instant recordedAt,
            int typeRank,
            String eventId)
    {
        public TimelineCursor
        {
            requireText(prId, "prId");
            requireNonNull(recordedAt, "recordedAt is null");
            requireText(eventId, "eventId");
            if (schemaVersion < 1 || eventCount < 1
                    || typeRank < 1 || typeRank > 1_000) {
                throw new IllegalArgumentException("cursor is invalid");
            }
        }
    }

    public record TimelinePage(
            PageStatus status,
            List<TimelineEvent> events,
            TimelineCursor nextCursor,
            boolean hasMore,
            long eventCount)
    {
        public TimelinePage
        {
            requireNonNull(status, "status is null");
            events = List.copyOf(events);
            if (eventCount < 1
                    || status == PageStatus.RESTART_REQUIRED
                        && (!events.isEmpty() || nextCursor != null || hasMore)) {
                throw new IllegalArgumentException("timeline page is invalid");
            }
        }
    }

    private record Snapshot(
            long eventCount, boolean cursorMatch, List<TimelineEvent> events) {}
}
