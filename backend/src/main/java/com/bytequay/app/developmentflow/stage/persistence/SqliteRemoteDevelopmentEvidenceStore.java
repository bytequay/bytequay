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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only exact evidence boundary used by the Remote Stage manager. */
public abstract class SqliteRemoteDevelopmentEvidenceStore
        implements RemoteDevelopmentStageManager.EvidenceStore
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteDevelopmentEvidenceStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<RemoteDevelopmentStageManager.FeedbackEvidence> findRemoteFeedback(
            String taskId, String stageId, long stageGeneration, String batchId)
    {
        return jdbc.query("""
                SELECT task_id, remote_development_stage_id, task_epoch,
                    stage_generation, id, source_snapshot_id, content_digest
                FROM remote_feedback_batch
                WHERE task_id = ? AND remote_development_stage_id = ?
                  AND stage_generation = ? AND id = ?
                  AND status = 'FROZEN' AND content_digest IS NOT NULL
                """, (rs, row) -> new RemoteDevelopmentStageManager.FeedbackEvidence(
                        rs.getString("task_id"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("id"),
                        rs.getString("source_snapshot_id"),
                        rs.getString("content_digest")),
                taskId, stageId, stageGeneration, batchId).stream().findFirst();
    }

    @Override
    public Optional<RemoteDevelopmentStageManager.MergeAuthorizationEvidence>
            findMergeAuthorization(
                    String taskId,
                    String stageId,
                    long stageGeneration,
                    String authorizationId)
    {
        return jdbc.query("""
                SELECT authorization.task_id, authorization.task_epoch,
                    authorization.remote_development_stage_id,
                    authorization.stage_generation,
                    authorization.id AS authorization_id,
                    authorization.readiness_evidence_id,
                    readiness.remote_pr_snapshot_id,
                    snapshot.observation_revision,
                    authorization.automation_policy_id,
                    operation.operation_id, operation.semantic_attempt,
                    authorization.head_sha, authorization.base_sha
                FROM remote_merge_authorization authorization
                JOIN remote_readiness_evidence readiness
                  ON readiness.id = authorization.readiness_evidence_id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = readiness.remote_pr_snapshot_id
                JOIN remote_merge_operation operation
                  ON operation.merge_authorization_id = authorization.id
                WHERE authorization.task_id = ?
                  AND authorization.remote_development_stage_id = ?
                  AND authorization.stage_generation = ?
                  AND authorization.id = ?
                  AND authorization.status = 'CONSUMED'
                  AND operation.status = 'REQUESTED'
                """, (rs, row) -> new RemoteDevelopmentStageManager.MergeAuthorizationEvidence(
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("authorization_id"),
                        rs.getString("readiness_evidence_id"),
                        rs.getLong("observation_revision"),
                        rs.getString("automation_policy_id"),
                        rs.getString("authorization_id"),
                        new ResultFence(
                                rs.getLong("task_epoch"),
                                rs.getString("remote_development_stage_id"),
                                rs.getLong("stage_generation"),
                                rs.getString("operation_id"),
                                rs.getInt("semantic_attempt"), null,
                                rs.getString("head_sha"),
                                rs.getString("base_sha"))),
                taskId, stageId, stageGeneration, authorizationId)
                .stream().findFirst();
    }

    @Override
    public Optional<RemoteDevelopmentStageManager.TerminalObservationEvidence>
            findTerminalObservation(
                    String taskId,
                    String stageId,
                    long stageGeneration,
                    String observationId)
    {
        return jdbc.query("""
                SELECT snapshot.task_id, snapshot.task_epoch,
                    snapshot.remote_development_stage_id,
                    snapshot.stage_generation, snapshot.id,
                    snapshot.observation_revision, snapshot.head_sha,
                    snapshot.base_sha, snapshot.pr_state
                FROM remote_pr_snapshot snapshot
                JOIN remote_development_stage remote
                  ON remote.stage_id = snapshot.remote_development_stage_id
                WHERE snapshot.task_id = ?
                  AND snapshot.remote_development_stage_id = ?
                  AND snapshot.stage_generation = ?
                  AND snapshot.id = ?
                  AND snapshot.pr_state IN ('MERGED', 'CLOSED')
                  AND remote.accepted_snapshot_id = snapshot.id
                  AND remote.current_head_sha = snapshot.head_sha
                  AND remote.current_base_sha = snapshot.base_sha
                """, (rs, row) ->
                        new RemoteDevelopmentStageManager.TerminalObservationEvidence(
                                rs.getString("task_id"), rs.getLong("task_epoch"),
                                rs.getString("remote_development_stage_id"),
                                rs.getLong("stage_generation"), rs.getString("id"),
                                rs.getLong("observation_revision"),
                                rs.getString("head_sha"), rs.getString("base_sha"),
                                RemoteDevelopmentStageManager.TerminalOutcome.valueOf(
                                        rs.getString("pr_state"))),
                taskId, stageId, stageGeneration, observationId)
                .stream().findFirst();
    }

    @Override
    public Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence>
            findCurrentRemoteSubject(String taskId, String stageId, long stageGeneration)
    {
        return jdbc.query("""
                SELECT remote.task_id, task.epoch, remote.stage_id,
                    remote.generation, remote.accepted_observation_revision,
                    remote.current_head_sha, remote.current_base_sha
                FROM remote_development_stage remote
                JOIN tasks task ON task.id = remote.task_id
                WHERE remote.task_id = ? AND remote.stage_id = ?
                  AND remote.generation = ?
                  AND remote.accepted_snapshot_id IS NOT NULL
                """, (rs, row) -> new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        rs.getString("task_id"), rs.getLong("epoch"),
                        rs.getString("stage_id"), rs.getLong("generation"),
                        rs.getLong("accepted_observation_revision"),
                        rs.getString("current_head_sha"),
                        rs.getString("current_base_sha")),
                taskId, stageId, stageGeneration).stream().findFirst();
    }
}
