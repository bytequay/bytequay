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

import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Idempotent owner proof for the RESUMING-to-ACTIVE rearm handoff. */
@Component
public final class SqliteStageResumeRearmStore
{
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SqliteStageResumeRearmStore(JdbcTemplate jdbc)
    {
        this(jdbc, Clock.systemUTC());
    }

    SqliteStageResumeRearmStore(JdbcTemplate jdbc, Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public TaskResumeOwner.Acceptance accept(
            TaskResumeOwner.Request request, StageKind ownerKind)
    {
        requireNonNull(request, "request is null");
        if (request.stageKind() != ownerKind) {
            throw new IllegalArgumentException(
                    ownerKind + " resume owner received " + request.stageKind());
        }
        Intent duplicate = find(request.handoffId());
        if (duplicate != null) {
            return requireReplay(duplicate, request, ownerKind);
        }
        String acceptedBy = ownerKind.name() + "_STAGE_OWNER";
        String proofId = id("stage-resume-rearm", request.handoffId(), ownerKind);
        try {
            jdbc.update("""
                    INSERT INTO stage_resume_rearm_intent_v257(
                        handoff_id, owner_proof_id, accepted_by, task_id,
                        task_epoch, task_version, stage_id, stage_kind,
                        stage_generation, stage_version, restore_checkpoint,
                        reconciliation_id, code_fingerprint, head_sha, base_sha,
                        status, accepted_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'PENDING', ?)
                    """, request.handoffId(), proofId, acceptedBy,
                    request.taskId(), request.taskEpoch(), request.taskVersion(),
                    request.stageId(), request.stageKind().name(),
                    request.stageGeneration(), request.stageVersion(),
                    request.restoreCheckpoint().name(), request.reconciliationId(),
                    request.codeFingerprint(), request.headSha(), request.baseSha(),
                    clock.millis());
        }
        catch (DataAccessException concurrent) {
            Intent replay = find(request.handoffId());
            if (replay == null) {
                throw concurrent;
            }
            return requireReplay(replay, request, ownerKind);
        }
        return requireReplay(requireNonNull(find(request.handoffId())), request, ownerKind);
    }

    private Intent find(String handoffId)
    {
        List<Intent> rows = jdbc.query("""
                SELECT handoff_id, owner_proof_id, accepted_by, task_id,
                       task_epoch, task_version, stage_id, stage_kind,
                       stage_generation, stage_version, restore_checkpoint,
                       reconciliation_id, code_fingerprint, head_sha, base_sha
                FROM stage_resume_rearm_intent_v257 WHERE handoff_id = ?
                """, (rs, row) -> new Intent(
                        rs.getString("handoff_id"),
                        rs.getString("owner_proof_id"),
                        rs.getString("accepted_by"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getLong("task_version"),
                        rs.getString("stage_id"),
                        StageKind.valueOf(rs.getString("stage_kind")),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"),
                        rs.getString("restore_checkpoint"),
                        rs.getString("reconciliation_id"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                handoffId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static TaskResumeOwner.Acceptance requireReplay(
            Intent intent, TaskResumeOwner.Request request, StageKind ownerKind)
    {
        if (!intent.taskId().equals(request.taskId())
                || intent.taskEpoch() != request.taskEpoch()
                || intent.taskVersion() != request.taskVersion()
                || !intent.stageId().equals(request.stageId())
                || intent.stageKind() != ownerKind
                || intent.stageGeneration() != request.stageGeneration()
                || intent.stageVersion() != request.stageVersion()
                || !intent.restoreCheckpoint().equals(
                        request.restoreCheckpoint().name())
                || !intent.reconciliationId().equals(request.reconciliationId())
                || !intent.codeFingerprint().equals(request.codeFingerprint())
                || !intent.headSha().equals(request.headSha())
                || !intent.baseSha().equals(request.baseSha())) {
            throw new IllegalArgumentException(
                    "Resume handoff id already names another Stage owner fence");
        }
        return new TaskResumeOwner.Acceptance(
                intent.handoffId(), intent.ownerProofId(), intent.acceptedBy());
    }

    private static String id(String namespace, Object... parts)
    {
        StringBuilder value = new StringBuilder(namespace);
        for (Object part : parts) {
            value.append('\u001f').append(part);
        }
        return UUID.nameUUIDFromBytes(
                value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Intent(
            String handoffId, String ownerProofId, String acceptedBy,
            String taskId, long taskEpoch, long taskVersion, String stageId,
            StageKind stageKind, long stageGeneration, long stageVersion,
            String restoreCheckpoint, String reconciliationId,
            String codeFingerprint, String headSha, String baseSha) {}
}
