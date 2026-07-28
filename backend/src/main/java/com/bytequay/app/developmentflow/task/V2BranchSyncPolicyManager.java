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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Sole Task-owned writer for the V2 scheduled branch-sync policy. */
public final class V2BranchSyncPolicyManager
{
    public static final int DEFAULT_ATTEMPT_LIMIT = 3;
    private static final String FIRST_PUSH_SOURCE = "FIRST_PUSH_DEFAULT";
    private static final String USER_SOURCE = "USER_CONFIGURED";

    private final TaskCommandExecutor commands;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public V2BranchSyncPolicyManager(
            TaskCommandExecutor commands, JdbcTemplate jdbc, Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** User configuration is one synchronous, immutable Task policy revision. */
    public Policy update(String taskId, Boolean enabled, String schedule)
    {
        requireText(taskId, "taskId");
        String commandId = UUID.randomUUID().toString();
        return commands.execute(taskId, () -> {
            Policy current = currentInCommand(taskId);
            boolean nextEnabled = enabled == null ? current.enabled() : enabled;
            String nextSchedule = schedule == null || schedule.isBlank()
                    ? current.schedule() : schedule.trim();
            validateSchedule(nextSchedule);
            if (current.persisted()
                    && current.enabled() == nextEnabled
                    && current.schedule().equals(nextSchedule)) {
                return current;
            }
            return append(
                    taskId, current.revision() + 1, nextEnabled, nextSchedule,
                    USER_SOURCE, current.attemptLimit(), commandId, "user");
        });
    }

    /** Arms the legacy-compatible default exactly once after accepted publish. */
    public Policy armOnFirstPushInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Policy current = currentInCommand(taskId);
        if (current.persisted()) {
            return current;
        }
        return append(
                taskId, 1, true, BranchGuard.SCHEDULE_NIGHTLY,
                FIRST_PUSH_SOURCE, DEFAULT_ATTEMPT_LIMIT,
                id("branch-sync-first-push", taskId), "system");
    }

    public Policy currentInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        return current(taskId);
    }

    public Policy current(String taskId)
    {
        requireText(taskId, "taskId");
        Integer taskCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tasks
                WHERE id = ? AND workflow_version = 'V2'
                """, Integer.class, taskId);
        if (taskCount == null || taskCount != 1) {
            throw new IllegalArgumentException("no V2 Task: " + taskId);
        }
        List<Policy> rows = jdbc.query("""
                SELECT id, task_id, revision, enabled, schedule, source,
                       attempt_limit, created_at_ms
                FROM task_branch_sync_policy_revision
                WHERE task_id = ?
                ORDER BY revision DESC
                LIMIT 1
                """, (rs, ignored) -> new Policy(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getInt("revision"), rs.getInt("enabled") == 1,
                        rs.getString("schedule"), rs.getString("source"),
                        rs.getInt("attempt_limit"),
                        Instant.ofEpochMilli(rs.getLong("created_at_ms"))),
                taskId);
        return rows.isEmpty() ? Policy.unarmed(taskId) : rows.getFirst();
    }

    private Policy append(
            String taskId,
            int revision,
            boolean enabled,
            String schedule,
            String source,
            int attemptLimit,
            String commandId,
            String actor)
    {
        Instant now = clock.instant();
        String policyId = id("task-branch-sync-policy", taskId + ":" + commandId);
        int inserted = jdbc.update("""
                INSERT INTO task_branch_sync_policy_revision(
                    id, task_id, revision, enabled, schedule, source,
                    attempt_limit, command_id, actor, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, policyId, taskId, revision, enabled ? 1 : 0, schedule,
                source, attemptLimit, commandId, actor, now.toEpochMilli());
        if (inserted != 1) {
            throw new IllegalStateException("Branch sync policy was not recorded");
        }
        return new Policy(
                policyId, taskId, revision, enabled, schedule, source,
                attemptLimit, now);
    }

    private static void validateSchedule(String schedule)
    {
        requireText(schedule, "schedule");
        if (schedule.length() > 64) {
            throw new IllegalArgumentException("schedule is too long");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Policy(
            String id,
            String taskId,
            int revision,
            boolean enabled,
            String schedule,
            String source,
            int attemptLimit,
            Instant createdAt)
    {
        private static Policy unarmed(String taskId)
        {
            return new Policy(
                    null, taskId, 0, false, BranchGuard.SCHEDULE_NIGHTLY,
                    "UNARMED_DEFAULT", DEFAULT_ATTEMPT_LIMIT, null);
        }

        public boolean persisted()
        {
            return id != null;
        }
    }
}
