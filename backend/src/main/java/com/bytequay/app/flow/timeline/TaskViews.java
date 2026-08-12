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

import com.bytequay.app.flow.timeline.PrTimelineProjection.OwnerType;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelineCursor;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelinePage;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Read-only rollups the run view needs on top of {@link PrTimelineProjection}.
 *
 * <p>The projection answers "what happened, in order". A run view also has to
 * answer "which runs exist", "what is this run's current shape", "how did each
 * CI round move the failing count", and "what is the body behind this event" —
 * none of which a flat event page can serve without fetching every row.
 *
 * <p>Like the projection this writes nothing, owns no storage, and reads only
 * the authoritative owner tables.
 */
public final class TaskViews
{
    public static final int MAX_LIST_SIZE = 100;

    /**
     * The one conclusion {@code CiAutofix} treats as a red required check;
     * anything else unaccepted becomes NEEDS_ATTENTION rather than a failure.
     */
    private static final String FAILING_CONCLUSION = "FAILURE";

    /**
     * {@code run_number} is the run view's "RUN #12" label. The Task table has
     * no sequence and deliberately no timestamps, so the number is derived from
     * creation order within one repository. It is a display label, never an
     * identity: numbering is computed across the whole repository so that a
     * single-Task lookup reports the same value a list does.
     */
    private static final String TASK_COLUMNS = """
            WITH task_time AS (
                SELECT t.task_id, t.repository_id,
                       (SELECT MIN(recorded_at)
                          FROM flow_runtime_task_lifecycle_revision l
                         WHERE l.task_id = t.task_id) AS created_at,
                       (SELECT MAX(recorded_at)
                          FROM flow_runtime_task_lifecycle_revision l
                         WHERE l.task_id = t.task_id) AS updated_at
                FROM flow_runtime_task t
            ),
            numbered AS (
                SELECT task_id, created_at, updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY repository_id
                           ORDER BY created_at, task_id
                       ) AS run_number
                FROM task_time
            )
            SELECT t.task_id, t.repository_id, t.goal_text, t.status,
                   t.branch_name, t.worktree_path, t.launch_base_sha,
                   t.current_base_sha, t.current_head_sha,
                   t.task_session_id, t.ci_session_id,
                   p.pr_id, p.current_remote_head,
                   r.pr_number, r.html_url,
                   n.run_number, n.created_at, n.updated_at
            FROM flow_runtime_task t
            JOIN numbered n ON n.task_id = t.task_id
            LEFT JOIN flow_runtime_pr p ON p.task_id = t.task_id
            LEFT JOIN flow_runtime_remote_identity r
                ON r.remote_identity_id = p.remote_identity_id
            """;

    private final JdbcTemplate jdbc;
    private final PrTimelineProjection timeline;

    public TaskViews(DataSource dataSource, PrTimelineProjection timeline)
    {
        jdbc = new JdbcTemplate(requireNonNull(dataSource, "dataSource is null"));
        this.timeline = requireNonNull(timeline, "timeline is null");
    }

    /** Newest first, so a run view opens on what the user last worked on. */
    public List<TaskSummary> list(String repositoryId, int limit)
    {
        requireText(repositoryId, "repositoryId");
        if (limit < 1 || limit > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return List.copyOf(jdbc.query(
                TASK_COLUMNS + """
                        WHERE t.repository_id = ?
                        ORDER BY n.created_at DESC, t.task_id
                        LIMIT ?
                        """,
                (rows, row) -> readSummary(rows),
                repositoryId,
                limit));
    }

    public Optional<TaskSummary> summary(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                        TASK_COLUMNS + "WHERE t.task_id = ?",
                        (rows, row) -> readSummary(rows),
                        taskId)
                .stream()
                .findFirst();
    }

    /**
     * One row per CI round, oldest first, with the failing count the run view
     * renders as "121 -> 27 failing" by reading consecutive rows.
     *
     * <p>Counts cover the frozen required-check selection only. Checks outside
     * the repository's required policy are never observed, so a total board
     * count is not derivable here and must not be presented as one.
     */
    public List<RoundView> rounds(String taskId)
    {
        requireText(taskId, "taskId");
        List<RoundView> ordered = jdbc.query(
                """
                SELECT r.round_id, r.remote_head, r.state, r.created_at,
                       (SELECT COUNT(*)
                          FROM json_each(r.check_observation_ids_json) j
                          JOIN flow_ci_check_observation o
                            ON o.observation_id = j.value
                       ) AS observed_count,
                       (SELECT COUNT(*)
                          FROM json_each(r.check_observation_ids_json) j
                          JOIN flow_ci_check_observation o
                            ON o.observation_id = j.value
                         WHERE upper(COALESCE(o.conclusion, '')) = ?
                       ) AS failing_count
                FROM flow_ci_round r
                WHERE r.task_id = ?
                  AND r.state <> 'SUPERSEDED'
                ORDER BY r.created_at, r.round_id
                """,
                (rows, row) -> new RoundView(
                        row + 1,
                        rows.getString("round_id"),
                        rows.getString("remote_head"),
                        rows.getString("state"),
                        rows.getInt("observed_count"),
                        rows.getInt("failing_count"),
                        Instant.ofEpochMilli(rows.getLong("created_at"))),
                FAILING_CONCLUSION,
                taskId);
        return List.copyOf(ordered);
    }

    /**
     * The body behind one timeline event. The projection deliberately returns
     * labels and an owner reference rather than content, so this is how the
     * view loads agent prose, check output, a CI log, or a lesson.
     */
    public Optional<EventDetail> detail(OwnerType type, String ownerId)
    {
        requireNonNull(type, "type is null");
        requireText(ownerId, "ownerId");
        return switch (type) {
            case AGENT_RESULT -> one(
                    """
                    SELECT terminal_outcome AS label, final_content AS body
                    FROM flow_runtime_agent_result
                    WHERE result_id = ?
                    """,
                    type, ownerId);
            case LOCAL_CHECK_RUN -> one(
                    """
                    SELECT conclusion AS label, output_text AS body
                    FROM flow_runtime_local_check_run
                    WHERE check_run_id = ?
                    """,
                    type, ownerId);
            case CI_LESSON -> one(
                    """
                    SELECT title AS label, markdown AS body
                    FROM flow_ci_lesson
                    WHERE lesson_id = ?
                    """,
                    type, ownerId);
            case CI_CHECK_OBSERVATION -> ciObservationDetail(ownerId);
            // Every other owner is fully described by its timeline event; there
            // is no second body to load, and inventing one would duplicate the
            // owner row the event already points at.
            default -> Optional.empty();
        };
    }

    /** Task-anchored page; empty until the Task materializes its one PR. */
    public Optional<TimelinePage> timeline(
            String taskId, TimelineCursor after, int limit)
    {
        requireText(taskId, "taskId");
        return summary(taskId)
                .map(TaskSummary::prId)
                .map(prId -> timeline.page(prId, after, limit));
    }

    /**
     * A CI observation's body is its stored log, which lives in a separate
     * owner row and may legitimately be absent — a check can be green, still
     * running, or have produced no retrievable log.
     */
    private Optional<EventDetail> ciObservationDetail(String observationId)
    {
        return jdbc.query(
                        """
                        SELECT o.name, o.status, o.conclusion,
                               e.sanitized_content, e.truncated
                        FROM flow_ci_check_observation o
                        LEFT JOIN flow_ci_log_evidence e
                            ON e.observation_id = o.observation_id
                        WHERE o.observation_id = ?
                        """,
                        (rows, row) -> {
                            byte[] log = rows.getBytes("sanitized_content");
                            return new EventDetail(
                                    OwnerType.CI_CHECK_OBSERVATION,
                                    observationId,
                                    rows.getString("name"),
                                    log == null
                                            ? null
                                            : new String(log, StandardCharsets.UTF_8),
                                    rows.getInt("truncated") == 1);
                        },
                        observationId)
                .stream()
                .findFirst();
    }

    private Optional<EventDetail> one(String sql, OwnerType type, String ownerId)
    {
        return jdbc.query(
                        sql,
                        (rows, row) -> new EventDetail(
                                type,
                                ownerId,
                                rows.getString("label"),
                                rows.getString("body"),
                                false),
                        ownerId)
                .stream()
                .findFirst();
    }

    private static TaskSummary readSummary(ResultSet rows)
            throws SQLException
    {
        long prNumber = rows.getLong("pr_number");
        boolean noPrNumber = rows.wasNull();
        return new TaskSummary(
                rows.getString("task_id"),
                rows.getInt("run_number"),
                rows.getString("repository_id"),
                rows.getString("goal_text"),
                rows.getString("status"),
                rows.getString("branch_name"),
                rows.getString("worktree_path"),
                rows.getString("launch_base_sha"),
                rows.getString("current_base_sha"),
                rows.getString("current_head_sha"),
                rows.getString("task_session_id"),
                rows.getString("ci_session_id"),
                rows.getString("pr_id"),
                rows.getString("current_remote_head"),
                noPrNumber ? null : prNumber,
                rows.getString("html_url"),
                instant(rows.getLong("created_at")),
                instant(rows.getLong("updated_at")));
    }

    private static Instant instant(long epochMillis)
    {
        return epochMillis == 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    /**
     * @param prNumber null until the Task's PR gains its GitHub identity.
     */
    public record TaskSummary(
            String taskId,
            int runNumber,
            String repositoryId,
            String goalText,
            String status,
            String branchName,
            String worktreePath,
            String launchBaseSha,
            String currentBaseSha,
            String currentHeadSha,
            String taskSessionId,
            String ciSessionId,
            String prId,
            String currentRemoteHead,
            Long prNumber,
            String prUrl,
            Instant createdAt,
            Instant updatedAt)
    {
        public TaskSummary
        {
            requireText(taskId, "taskId");
            requireText(repositoryId, "repositoryId");
            requireNonNull(status, "status is null");
            if (runNumber < 1) {
                throw new IllegalArgumentException("runNumber must be positive");
            }
        }
    }

    /**
     * @param ordinal display position only, derived from creation order over
     *         the rounds this PR still counts. It is not a stored sequence and
     *         must not be used as an identity.
     * @param observedCount frozen required checks in this round, not the size
     *         of the provider's whole check board.
     */
    public record RoundView(
            int ordinal,
            String roundId,
            String remoteHead,
            String state,
            int observedCount,
            int failingCount,
            Instant createdAt)
    {
        public RoundView
        {
            requireText(roundId, "roundId");
            requireText(remoteHead, "remoteHead");
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
            if (ordinal < 1 || failingCount < 0 || observedCount < failingCount) {
                throw new IllegalArgumentException("round view is invalid");
            }
        }
    }

    /**
     * @param body null when the owner has no retrievable content, which is a
     *         normal state rather than an error.
     */
    public record EventDetail(
            OwnerType type,
            String ownerId,
            String label,
            String body,
            boolean truncated)
    {
        public EventDetail
        {
            requireNonNull(type, "type is null");
            requireText(ownerId, "ownerId");
        }
    }
}
