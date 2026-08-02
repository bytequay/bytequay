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

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemotePolicyRedriveRuntime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Thin restart redriver; Plan and Remote owners keep every transition. */
@Component
public final class TaskPolicyRevisionRedriver
        implements V2TaskControlService.PolicyRevisionRedriver,
        ExecutionPorts.MaintenanceWork
{
    private static final int SCAN_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final PlanRuntimeCoordinator plans;
    private final RemotePolicyRedriveRuntime remote;

    public TaskPolicyRevisionRedriver(
            JdbcTemplate jdbc,
            PlanRuntimeCoordinator plans,
            RemotePolicyRedriveRuntime remote)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.plans = requireNonNull(plans, "plans is null");
        this.remote = requireNonNull(remote, "remote is null");
    }

    @Override
    public void redrive(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        plans.redrivePolicyApproval(taskId);
        remote.redrive(taskId);
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        RuntimeException first = null;
        for (String taskId : pendingTaskIds()) {
            try {
                redrive(taskId);
            }
            catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                }
                else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private List<String> pendingTaskIds()
    {
        return jdbc.queryForList("""
                SELECT task.id
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_generation = owner.generation
                  AND owner.completed_at_ms IS NULL
                  AND (
                    (owner.kind = 'PLAN'
                      AND owner.checkpoint = 'AWAITING_APPROVAL'
                      AND EXISTS (
                        SELECT 1 FROM task_policy_revision policy
                        WHERE policy.id = task.policy_revision_id
                          AND policy.auto_approve = 1))
                    OR
                    (owner.kind = 'REMOTE_DEVELOPMENT'
                      AND owner.checkpoint IN (
                        'WAITING_REMOTE_REVIEW', 'READY_TO_MERGE')
                      AND EXISTS (
                        SELECT 1
                        FROM remote_development_stage remote
                        JOIN remote_pr_snapshot snapshot
                          ON snapshot.id = remote.accepted_snapshot_id
                        JOIN task_automation_policy policy
                          ON policy.task_id = task.id
                         AND policy.revision = (
                           SELECT MAX(latest.revision)
                           FROM task_automation_policy latest
                           WHERE latest.task_id = task.id)
                        LEFT JOIN remote_readiness_evidence readiness
                          ON readiness.remote_pr_snapshot_id =
                               remote.accepted_snapshot_id
                         AND readiness.automation_policy_id = policy.id
                         AND readiness.head_sha = remote.current_head_sha
                         AND readiness.base_sha = remote.current_base_sha
                        WHERE remote.stage_id = owner.id
                          AND remote.generation = owner.generation
                          AND snapshot.merge_queue_capability <> 'UNKNOWN'
                          AND (
                            readiness.id IS NULL
                            OR (owner.checkpoint = 'WAITING_REMOTE_REVIEW'
                                AND readiness.ready = 1)
                            OR (owner.checkpoint = 'READY_TO_MERGE'
                                AND readiness.ready = 0)
                            OR (owner.checkpoint = 'READY_TO_MERGE'
                                AND readiness.ready = 1
                                AND policy.auto_merge = 1
                                AND policy.stewardship_exception = 0
                                AND NOT EXISTS (
                                  SELECT 1
                                  FROM remote_merge_authorization authorization
                                  WHERE authorization.readiness_evidence_id =
                                      readiness.id)))))
                  )
                ORDER BY task.created_at_ms, task.id
                LIMIT ?
                """, String.class, SCAN_LIMIT);
    }
}
