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
package com.bytequay.app.developmentflow.compatibility;

import com.bytequay.app.domain.BranchGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Read-only BranchGuard shape derived exclusively from typed V2 facts. */
@Component
public final class V2BranchGuardProjection
{
    private final JdbcTemplate jdbc;

    public V2BranchGuardProjection(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public BranchGuard project(String taskId)
    {
        requireText(taskId, "taskId");
        if (!tableExists("task_branch_sync_policy_revision")) {
            return BranchGuard.disabled(taskId);
        }
        Policy policy = policy(taskId);
        RemoteHealth health = remoteHealth(taskId);
        Episode episode = latestEpisode(taskId);
        boolean drifting = health != null
                && health.localBaseSha() != null
                && health.remoteBaseSha() != null
                && !Objects.equals(
                        health.localBaseSha(), health.remoteBaseSha());
        String state = state(episode, health, drifting);
        BranchGuard.Health projectedHealth = health == null
                ? BranchGuard.Health.UNKNOWN
                : new BranchGuard.Health(
                        health.localBaseSha() == null
                                || health.remoteBaseSha() == null
                                || drifting ? null : 0,
                        mergeable(health.mergeability()),
                        checksGreen(health.ciOutcome()));
        return new BranchGuard(
                taskId, policy.enabled(), policy.schedule(), state,
                projectedHealth, episode == null ? null : episode.id(),
                health == null || health.observedAtMs() == null
                        ? null : Instant.ofEpochMilli(health.observedAtMs()));
    }

    private Policy policy(String taskId)
    {
        List<Policy> rows = jdbc.query("""
                SELECT enabled, schedule
                FROM task_branch_sync_policy_revision
                WHERE task_id = ?
                ORDER BY revision DESC
                LIMIT 1
                """, (rs, ignored) -> new Policy(
                        rs.getInt("enabled") == 1,
                        rs.getString("schedule")), taskId);
        return rows.isEmpty()
                ? new Policy(false, BranchGuard.SCHEDULE_NIGHTLY)
                : rows.getFirst();
    }

    private RemoteHealth remoteHealth(String taskId)
    {
        return jdbc.query("""
                SELECT snapshot.base_sha AS remote_base_sha,
                       snapshot.mergeability,
                       snapshot.observed_at_ms,
                       code.base_sha AS local_base_sha,
                       evaluation.policy_outcome AS ci_outcome
                FROM tasks task
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                LEFT JOIN remote_development_stage remote ON remote.stage_id = (
                    SELECT candidate.stage_id
                    FROM remote_development_stage candidate
                    WHERE candidate.task_id = task.id
                    ORDER BY candidate.subject_changed_at_ms DESC,
                             candidate.stage_id DESC
                    LIMIT 1)
                LEFT JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                LEFT JOIN remote_ci_evaluation evaluation ON evaluation.id = (
                    SELECT candidate.id
                    FROM remote_ci_evaluation candidate
                    WHERE candidate.remote_pr_snapshot_id = snapshot.id
                    ORDER BY candidate.evaluated_at_ms DESC, candidate.id DESC
                    LIMIT 1)
                WHERE task.id = ? AND task.workflow_version = 'V2'
                """, (rs, ignored) -> new RemoteHealth(
                        rs.getString("local_base_sha"),
                        rs.getString("remote_base_sha"),
                        rs.getString("mergeability"),
                        rs.getString("ci_outcome"),
                        nullableLong(rs.getObject("observed_at_ms"))), taskId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task is not routed through V2: " + taskId));
    }

    private Episode latestEpisode(String taskId)
    {
        return jdbc.query("""
                SELECT episode.id, episode.status,
                       EXISTS (
                           SELECT 1 FROM task_blocker blocker
                           WHERE blocker.owner_kind = 'EPISODE'
                             AND blocker.owner_id = episode.id
                             AND blocker.status = 'OPEN') AS blocked
                FROM branch_sync_episode episode
                WHERE episode.task_id = ?
                ORDER BY episode.opened_at_ms DESC, episode.id DESC
                LIMIT 1
                """, (rs, ignored) -> new Episode(
                        rs.getString("id"), rs.getString("status"),
                        rs.getInt("blocked") == 1), taskId)
                .stream().findFirst().orElse(null);
    }

    private boolean tableExists(String table)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private static String state(
            Episode episode, RemoteHealth health, boolean drifting)
    {
        if (episode != null) {
            if ("CONFLICT_REPAIR".equals(episode.status())) {
                return BranchGuard.STATE_CONFLICTED;
            }
            if (!List.of("SUCCEEDED", "FAILED", "STOPPED")
                    .contains(episode.status())) {
                return BranchGuard.STATE_FIXING;
            }
            if (episode.blocked()
                    || "FAILED".equals(episode.status())
                    || "STOPPED".equals(episode.status())) {
                return BranchGuard.STATE_NEEDS_ATTENTION;
            }
        }
        if (drifting && health != null
                && "CONFLICTING".equals(health.mergeability())) {
            return BranchGuard.STATE_CONFLICTED;
        }
        return drifting ? BranchGuard.STATE_DRIFTING
                : BranchGuard.STATE_HEALTHY;
    }

    private static Boolean mergeable(String value)
    {
        if ("MERGEABLE".equals(value)) {
            return Boolean.TRUE;
        }
        return "CONFLICTING".equals(value) ? Boolean.FALSE : null;
    }

    private static Boolean checksGreen(String value)
    {
        if ("ACCEPTED".equals(value)) {
            return Boolean.TRUE;
        }
        return "FAILED".equals(value) ? Boolean.FALSE : null;
    }

    private static Long nullableLong(Object value)
    {
        return value == null ? null : ((Number) value).longValue();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record Policy(boolean enabled, String schedule) {}

    private record RemoteHealth(
            String localBaseSha,
            String remoteBaseSha,
            String mergeability,
            String ciOutcome,
            Long observedAtMs) {}

    private record Episode(String id, String status, boolean blocked) {}
}
