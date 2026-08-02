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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Redrives standing Local auto-publish consent without owning execution. */
@Component
public final class LocalAutoPublishRedriver
        implements ExecutionPorts.MaintenanceWork
{
    private static final Logger log = LoggerFactory.getLogger(
            LocalAutoPublishRedriver.class);
    private static final int SCAN_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final V2PrRemoteControlService controls;

    public LocalAutoPublishRedriver(
            JdbcTemplate jdbc, V2PrRemoteControlService controls)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.controls = requireNonNull(controls, "controls is null");
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        RuntimeException first = null;
        for (Candidate candidate : candidates()) {
            try {
                controls.approveAndShip(
                        candidate.commandId(), candidate.taskId(),
                        candidate.prId(), false);
            }
            catch (ResponseStatusException blocked) {
                // The owner performs the definitive fresh-state check. A
                // newly added blocker is simply reconsidered by the next
                // maintenance pass.
                log.debug("Local auto-publish remains blocked for Task {}: {}",
                        candidate.taskId(), blocked.getReason());
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

    private List<Candidate> candidates()
    {
        return jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                       task.policy_revision_id,
                       stage.id AS stage_id, stage.generation,
                       stage.version AS stage_version,
                       report.id AS report_id,
                       validation.id AS validation_id,
                       brain.id AS brain_id,
                       pr.id AS pr_id
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage stage ON stage.id = current.stage_id
                JOIN local_development_stage local ON local.stage_id = stage.id
                JOIN task_policy_revision policy
                  ON policy.id = task.policy_revision_id
                JOIN dev_report report
                  ON report.local_development_stage_id = stage.id
                 AND report.revision = (
                     SELECT MAX(latest.revision) FROM dev_report latest
                     WHERE latest.workflow_version = 'V2'
                       AND latest.local_development_stage_id = stage.id)
                JOIN validation_operation validation_operation
                  ON validation_operation.dev_report_id = report.id
                 AND validation_operation.semantic_attempt = (
                     SELECT MAX(latest.semantic_attempt)
                     FROM validation_operation latest
                     WHERE latest.dev_report_id = report.id)
                 AND validation_operation.status = 'COMPLETED'
                JOIN validation_evidence validation
                  ON validation.validation_operation_id = validation_operation.id
                 AND validation.passed = 1
                JOIN brain_review_episode brain
                  ON brain.dev_report_id = report.id
                 AND brain.semantic_attempt = (
                     SELECT MAX(latest.semantic_attempt)
                     FROM brain_review_episode latest
                     WHERE latest.dev_report_id = report.id)
                 AND brain.status = 'SUCCEEDED'
                 AND brain.verdict = 'APPROVED'
                 AND brain.unresolved_finding_count = 0
                JOIN pr pr ON pr.task_id = task.id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND policy.auto_approve = 1
                  AND stage.kind = 'LOCAL_DEVELOPMENT'
                  AND stage.checkpoint = 'LOCAL_REVIEW'
                  AND stage.completed_at_ms IS NULL
                  AND current.stage_generation = stage.generation
                  AND local.generation = stage.generation
                  AND local.opened_for_epoch = task.epoch
                  AND report.task_epoch = task.epoch
                  AND report.stage_generation = stage.generation
                  AND pr.origin = 'task' AND pr.status = 'local-open'
                  AND NOT EXISTS (
                      SELECT 1 FROM task_blocker blocker
                      WHERE blocker.task_id = task.id
                        AND blocker.stage_id = stage.id
                        AND blocker.status = 'OPEN'
                        AND blocker.blocker_type IN (
                          'LOCAL_VALIDATION_FAILED', 'LOCAL_FEEDBACK_OPEN',
                          'LOCAL_AGENT_REVIEW_BLOCKING'))
                  AND NOT EXISTS (
                      SELECT 1 FROM publish_operation operation
                      WHERE operation.task_id = task.id
                        AND operation.status IN ('REQUESTED', 'DISPATCHED'))
                  AND NOT EXISTS (
                      SELECT 1 FROM publish_operation terminal
                      WHERE terminal.task_id = task.id
                        AND terminal.task_epoch = task.epoch
                        AND terminal.local_development_stage_id = stage.id
                        AND terminal.stage_generation = stage.generation
                        AND terminal.code_fingerprint = report.code_fingerprint
                        AND terminal.expected_head_sha = report.head_sha
                        AND terminal.expected_base_sha = report.base_sha
                        AND terminal.status IN ('FAILED', 'CANCELED'))
                ORDER BY task.created_at_ms, task.id
                LIMIT ?
                """, (rs, row) -> candidate(
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("policy_revision_id"), rs.getString("stage_id"),
                rs.getLong("generation"), rs.getLong("stage_version"),
                rs.getString("report_id"), rs.getString("validation_id"),
                rs.getString("brain_id"), rs.getString("pr_id")), SCAN_LIMIT);
    }

    private static Candidate candidate(
            String taskId, long taskEpoch, String policyRevisionId,
            String stageId, long stageGeneration, long stageVersion,
            String reportId, String validationId, String brainId, String prId)
    {
        String subject = String.join(":",
                taskId, Long.toString(taskEpoch), policyRevisionId, stageId,
                Long.toString(stageGeneration), Long.toString(stageVersion),
                reportId, validationId, brainId, prId);
        return new Candidate(
                taskId, prId, id("local-auto-publish-command", subject));
    }

    private record Candidate(String taskId, String prId, String commandId) {}
}
