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

import com.bytequay.app.beans.stage.ContextWindowDto;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailData.BranchSyncRecovery;
import com.bytequay.app.beans.stage.StageDetailData.CiRecovery;
import com.bytequay.app.beans.stage.StageDetailData.CleanupRecovery;
import com.bytequay.app.beans.stage.StageDetailData.ConversationRow;
import com.bytequay.app.beans.stage.StageDetailData.DetailTask;
import com.bytequay.app.beans.stage.StageDetailData.FailedStageTurnRecovery;
import com.bytequay.app.beans.stage.StageDetailData.IterationDetail;
import com.bytequay.app.beans.stage.StageDetailData.LocalPublishBaseSyncRecovery;
import com.bytequay.app.beans.stage.StageDetailData.RecoveryOptions;
import com.bytequay.app.beans.stage.StageDetailData.Scrubber;
import com.bytequay.app.beans.stage.StageDetailData.StageConfig;
import com.bytequay.app.beans.stage.StageDetailData.StageInfo;
import com.bytequay.app.beans.stage.StageDetailData.StageMetricsSubset;
import com.bytequay.app.beans.stage.StageDetailData.StageTurnRecovery;
import com.bytequay.app.beans.stage.StageDetailData.WorktreeQuarantineRecovery;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.StreamJsonParser;
import com.bytequay.app.service.threads.StreamLine;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Compatibility adapter for Stage-detail HTTP routes whose immutable owner is
 * V2. It reads only typed V2 facts and controls exact DispatchTickets; it must
 * never resolve a legacy StageStore or ThreadRegistry agent.
 */
@Service
public final class V2StageApiService
{
    private static final int CONTEXT_TOKEN_LIMIT = 200_000;
    private static final long STREAM_POLL_MS = 100;

    private final JdbcTemplate jdbc;
    private final V2DevelopmentFlowProjection projection;
    private final V2BranchGuardProjection branchGuards;
    private final DispatchTicketControl tickets;
    private final ObjectMapper json;

    public V2StageApiService(
            JdbcTemplate jdbc,
            V2DevelopmentFlowProjection projection,
            V2BranchGuardProjection branchGuards,
            DispatchTicketControl tickets,
            ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.projection = requireNonNull(projection, "projection is null");
        this.branchGuards = requireNonNull(
                branchGuards, "branchGuards is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.json = requireNonNull(json, "json is null");
    }

    public StageDetailData detail(String taskId, String stageId)
    {
        StageFacts stage = requireStage(taskId, stageId);
        List<TurnFacts> turns = turns(stageId);
        List<ConversationRow> conversation = conversation(stageId, turns);
        return detail(stage, turns, conversation, usage(stageId),
                activeAgent(taskId, stage));
    }

    /** Stage-detail compatibility shape containing only one exact typed Turn. */
    public StageDetailData runDetail(String runId)
    {
        RunFacts run = requireRun(runId);
        StageFacts stage = requireStage(run.taskId(), run.stageId());
        List<TurnFacts> turns = List.of(run.turn());
        return detail(stage, turns, conversation(run), runUsage(run.ticketId()),
                run.live());
    }

    private StageDetailData detail(
            StageFacts stage,
            List<TurnFacts> turns,
            List<ConversationRow> conversation,
            Usage usage,
            boolean activeAgent)
    {
        List<StageDto> allStages = projection.stages(stage.taskId());
        long closedAt = stage.completedAtMs() == null
                ? System.currentTimeMillis() : stage.completedAtMs();
        long wallTime = Math.max(0, (closedAt - stage.openedAtMs()) / 1000);
        List<ScrubberDash> scrubbers = conversation.stream()
                .filter(row -> "user".equals(row.kind()))
                .map(row -> new ScrubberDash(row.id(), row.ts(), false))
                .toList();

        return new StageDetailData(
                new DetailTask(
                        stage.taskId(), stage.taskNumber(), stage.title(),
                        text(stage.branch()), text(stage.repositoryId()),
                        stage.prNumber(), stage.prDraft(),
                        // The shell's Stage rail is Task-level: it must report
                        // where the Task is now, not the Stage being read. A
                        // closed Local Development page otherwise re-derived
                        // the whole ladder from its own COMPLETED checkpoint.
                        legacyPhase(
                                stage.currentKind() != null
                                        ? stage.currentKind() : stage.kind(),
                                stage.currentCheckpoint() != null
                                        ? stage.currentCheckpoint()
                                        : stage.checkpoint()),
                        runtime(stage.runtime()), text(stage.model())),
                new StageInfo(
                        stage.id(), legacyStageType(stage.kind()),
                        stage.completedAtMs() == null ? "OPEN" : "CLOSED",
                        instant(stage.openedAtMs()).toString(),
                        stage.completedAtMs() == null ? null
                                : instant(stage.completedAtMs()).toString(),
                        null, turns.size(), currentIteration(turns),
                        activeAgent,
                        new StageConfig(null, false),
                        new StageMetricsSubset(
                                wallTime, turns.size(), usage.toolCalls(),
                                turns.size(), conversation.size(), usage.tokens(),
                                usage.costUsdMilli() / 10, 0,
                                null, null, null,
                                (int) conversation.stream()
                                        .filter(row -> "user".equals(row.kind()))
                                        .count(),
                                null, terminalState(stage))),
                allStages,
                List.of(),
                null,
                iterations(turns),
                conversation,
                null,
                List.of(),
                null,
                new ContextWindowDto(
                        toIntExact(Math.min(Integer.MAX_VALUE, usage.tokens())),
                        CONTEXT_TOKEN_LIMIT, contextBand(usage.tokens())),
                new Scrubber(scrubbers),
                List.of(),
                branchGuards.project(stage.taskId()),
                null,
                List.of(),
                recovery(stage));
    }

    private RecoveryOptions recovery(StageFacts stage)
    {
        CiRecovery ci = jdbc.query("""
                SELECT episode.id, blocker.id AS blocker_id,
                       blocker.blocker_type,
                       episode.rerun_count, episode.rerun_limit,
                       episode.fix_attempt_count, episode.fix_attempt_limit,
                       episode.push_count, episode.push_limit
                  FROM ci_repair_episode episode
                  JOIN tasks task ON task.id = episode.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  JOIN remote_development_stage remote
                    ON remote.stage_id = episode.remote_development_stage_id
                  JOIN task_blocker blocker
                    ON blocker.task_id = episode.task_id
                   AND blocker.stage_id = episode.remote_development_stage_id
                   AND blocker.status = 'OPEN'
                   AND ((blocker.owner_kind = 'EPISODE'
                         AND blocker.owner_id = episode.id)
                     OR (blocker.blocker_type = 'CI_BRANCH_SYNC_REQUIRED'
                         AND blocker.owner_kind = 'STAGE'
                         AND blocker.owner_id =
                             episode.remote_development_stage_id
                         AND blocker.subject_revision =
                             remote.accepted_snapshot_id)
                     OR (blocker.blocker_type =
                            'WORKTREE_RESTORE_QUARANTINED'
                         AND blocker.owner_kind = 'OPERATION'
                         AND EXISTS (
                           SELECT 1
                             FROM agent_turn_worktree_quarantine_v318 quarantine
                            WHERE quarantine.id = blocker.subject_revision
                              AND quarantine.status = 'OPEN'
                              AND quarantine.task_id = episode.task_id
                              AND quarantine.stage_id =
                                  episode.remote_development_stage_id
                              AND quarantine.source_operation_id =
                                  blocker.owner_id
                              AND (EXISTS (
                                    SELECT 1
                                      FROM ci_repair_operation source
                                     WHERE source.ci_repair_episode_id =
                                           episode.id
                                       AND source.operation_id =
                                           quarantine.source_operation_id)
                                OR EXISTS (
                                    SELECT 1
                                      FROM ci_repair_fix_continuation_operation_v318
                                           source
                                     WHERE source.ci_repair_episode_id =
                                           episode.id
                                       AND source.operation_id =
                                           quarantine.source_operation_id)))))
                 WHERE episode.task_id = ?
                   AND episode.remote_development_stage_id = ?
                   AND ((episode.status = 'EXHAUSTED'
                         AND blocker.blocker_type = 'CI_BUDGET_EXHAUSTED')
                     OR (episode.status = 'OPEN'
                         AND episode.classification = 'BASE_DETERMINISTIC'
                         AND blocker.blocker_type =
                             'CI_BASE_REPAIR_REQUIRED')
                     OR (episode.status NOT IN (
                             'SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                         AND blocker.blocker_type IN (
                             'CI_REPAIR_NO_CHANGE',
                             'CI_REPAIR_NO_CHANGE_RETRY_EXHAUSTED',
                             'CI_REPAIR_OUTPUT_PROOF_MISSING',
                             'CI_REPAIR_OUTPUT_MALFORMED',
                             'CI_REPAIR_TURN_FAILED',
                             'CI_BRANCH_SYNC_REQUIRED'))
                     OR (episode.status = 'STOPPED'
                         AND blocker.blocker_type =
                             'WORKTREE_RESTORE_QUARANTINED'))
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = episode.task_epoch
                   AND current.stage_id = episode.remote_development_stage_id
                   AND current.stage_generation = episode.stage_generation
                   AND owner.task_id = task.id
                   AND owner.kind = 'REMOTE_DEVELOPMENT'
                   AND owner.generation = episode.stage_generation
                   AND owner.completed_at_ms IS NULL
                 ORDER BY CASE WHEN blocker.blocker_type =
                                      'WORKTREE_RESTORE_QUARANTINED'
                               THEN 0 ELSE 1 END,
                          COALESCE(episode.completed_at_ms,
                                   episode.opened_at_ms) DESC,
                          episode.id
                """, (rs, row) -> {
                    String blockerType = rs.getString("blocker_type");
                    boolean baseRepair = "CI_BASE_REPAIR_REQUIRED".equals(
                            blockerType);
                    boolean noChange = "CI_REPAIR_NO_CHANGE".equals(
                            blockerType);
                    boolean branchSync = "CI_BRANCH_SYNC_REQUIRED".equals(
                            blockerType);
                    boolean missingProof =
                            "CI_REPAIR_OUTPUT_PROOF_MISSING".equals(
                                    blockerType);
                    boolean malformedOutput =
                            "CI_REPAIR_OUTPUT_MALFORMED".equals(blockerType);
                    boolean failedTurn =
                            "CI_REPAIR_TURN_FAILED".equals(blockerType);
                    boolean noChangeRetryExhausted =
                            "CI_REPAIR_NO_CHANGE_RETRY_EXHAUSTED".equals(
                                    blockerType);
                    boolean quarantined =
                            "WORKTREE_RESTORE_QUARANTINED".equals(
                                    blockerType);
                    return new CiRecovery(
                        rs.getString("id"), rs.getString("blocker_id"),
                        blockerType,
                        baseRepair
                                ? "A proven base-owned CI failure needs approval"
                                : noChange
                                    ? "Two CI repair turns made no committed tree change"
                                : branchSync
                                    ? "CI repair needs the Task branch synchronized first"
                                : missingProof
                                    ? "CI repair output lacks exact writer proof"
                                : malformedOutput
                                    ? "CI repair returned malformed strict output"
                                : failedTurn
                                    ? "CI repair execution failed on this exact subject"
                                : quarantined
                                    ? "CI repair could not restore the exact Task worktree"
                                : noChangeRetryExhausted
                                    ? "The final authorized CI repair still made no committed tree change"
                                : "The CI fixing budget is exhausted",
                        rs.getInt("rerun_count"), rs.getInt("rerun_limit"),
                        rs.getInt("fix_attempt_count"),
                        rs.getInt("fix_attempt_limit"),
                        rs.getInt("push_count"), rs.getInt("push_limit"),
                        baseRepair
                                ? List.of("START_BASE_REPAIR",
                                        "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : noChange
                                    ? List.of("RETRY_ONCE", "MANUAL_TAKEOVER",
                                            "STOP_AUTOMATION")
                                : branchSync
                                    ? List.of("START_BRANCH_SYNC",
                                            "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : missingProof
                                    ? List.of(
                                            "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : malformedOutput || failedTurn
                                    ? List.of(
                                            "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : quarantined
                                    ? List.of(
                                            "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : noChangeRetryExhausted
                                    ? List.of(
                                            "MANUAL_TAKEOVER", "STOP_AUTOMATION")
                                : List.of("EXTEND_BUDGET",
                                        "CONTINUE_WITH_PER_PUSH_APPROVAL",
                                        "MANUAL_TAKEOVER", "STOP_AUTOMATION"));
                },
                stage.taskId(), stage.id()).stream().findFirst().orElse(null);

        CleanupRecovery cleanup = jdbc.query("""
                SELECT step.id, step.kind, step.requirement,
                       step.attempt_count, step.attempt_limit, step.last_error,
                       step.failure_kind, step.execute_attempt_count,
                       EXISTS (
                           SELECT 1 FROM cleanup_step_retry_request retry
                            WHERE retry.cleanup_step_id = step.id
                              AND retry.status = 'PENDING') AS retry_requested
                  FROM cleanup_step step
                  JOIN cleanup_operation operation
                    ON operation.id = step.cleanup_operation_id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                 WHERE step.task_id = ? AND step.cleanup_stage_id = ?
                   AND step.status = 'FAILED'
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'CLEANING'
                   AND task.epoch = operation.task_epoch
                   AND operation.status = 'ACTIVE'
                   AND current.stage_id = operation.cleanup_stage_id
                   AND current.stage_generation = operation.stage_generation
                   AND owner.task_id = task.id AND owner.kind = 'CLEANUP'
                   AND owner.generation = operation.stage_generation
                   AND owner.checkpoint = 'CLEANING'
                   AND owner.completed_at_ms IS NULL
                   AND ticket.operation_id = operation.operation_id
                   AND ticket.status = 'RECONCILE_WAIT'
                   AND ticket.next_attempt_at_ms IS NULL
                   AND ticket.cancel_requested_at_ms IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM cleanup_step earlier
                        WHERE earlier.cleanup_operation_id = operation.id
                          AND earlier.ordinal < step.ordinal
                          AND earlier.status NOT IN (
                              'SUCCEEDED', 'SKIPPED', 'WAIVED'))
                   AND EXISTS (
                       SELECT 1 FROM task_blocker blocker
                        WHERE blocker.task_id = task.id
                          AND blocker.stage_id = owner.id
                          AND blocker.owner_kind = 'OPERATION'
                          AND blocker.owner_id = operation.id
                          AND blocker.subject_revision = CAST(step.ordinal AS TEXT)
                          AND blocker.blocker_type = 'CLEANUP_STEP_FAILED'
                          AND blocker.status = 'OPEN')
                 ORDER BY step.ordinal
                """, (rs, row) -> {
                    boolean retry = "DETERMINATE".equals(
                            rs.getString("failure_kind"))
                            && rs.getInt("execute_attempt_count")
                                    < rs.getInt("attempt_limit")
                            && !rs.getBoolean("retry_requested");
                    boolean waive = "OPTIONAL".equals(
                            rs.getString("requirement"))
                            && !rs.getBoolean("retry_requested");
                    List<String> actions = new ArrayList<>(2);
                    if (retry) {
                        actions.add("RETRY");
                    }
                    if (waive) {
                        actions.add("WAIVE_OPTIONAL");
                    }
                    return new CleanupRecovery(
                            rs.getString("id"), rs.getString("kind"),
                            rs.getString("requirement"),
                            rs.getInt("attempt_count"),
                            rs.getInt("attempt_limit"),
                            rs.getString("last_error"), List.copyOf(actions));
                }, stage.taskId(), stage.id()).stream()
                .filter(value -> !value.actions().isEmpty())
                .findFirst().orElse(null);
        List<StageTurnRecovery> replacements = jdbc.query("""
                SELECT turn.id AS stage_turn_id, ticket.last_error
                  FROM dispatch_ticket ticket
                  JOIN stage_turn turn
                    ON turn.id = ticket.owner_id
                   AND turn.operation_id = ticket.operation_id
                  JOIN tasks task ON task.id = ticket.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                 WHERE task.id = ? AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = ticket.task_epoch
                   AND owner.id = ? AND owner.task_id = task.id
                   AND owner.generation = current.stage_generation
                   AND owner.generation = ticket.stage_generation
                   AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
                   AND owner.kind IN ('LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT')
                   AND turn.stage_id = owner.id
                   AND turn.stage_generation = owner.generation
                   AND turn.task_epoch = task.epoch
                   AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                   AND ticket.owner_kind = 'STAGE_TURN'
                   AND ticket.stage_id = owner.id
                   AND ticket.status = 'RESULT_PENDING'
                   AND ticket.pending_result_outcome = 'SUCCEEDED'
                   AND ticket.delivery_acceptance IS NULL
                   AND ticket.last_error LIKE ?
                   AND EXISTS (SELECT 1 FROM agent_execution execution
                        WHERE execution.ticket_id = ticket.id
                          AND execution.infrastructure_attempt =
                              ticket.infrastructure_attempts
                          AND execution.status = 'SUCCEEDED'
                          AND execution.finished_at_ms IS NOT NULL
                          AND execution.raw_result IS NOT NULL)
                   AND ((owner.kind = 'LOCAL_DEVELOPMENT'
                         AND owner.checkpoint IN ('IMPLEMENTING',
                             'ADDRESSING_BRAIN_FINDINGS',
                             'ADDRESSING_LOCAL_FEEDBACK', 'LOCAL_REVIEW')
                         AND ticket.callback_route = 'STAGE_TURN_RESULT'
                         AND EXISTS (
                             SELECT 1 FROM local_stage_turn_request request
                              WHERE request.stage_turn_id = turn.id
                                AND request.task_id = task.id
                                AND request.local_development_stage_id = owner.id
                                AND request.task_epoch = task.epoch
                                AND request.stage_generation = owner.generation))
                     OR (owner.kind = 'REMOTE_DEVELOPMENT'
                         AND turn.purpose IN ('REMOTE_CI_REPAIR',
                             'BRANCH_CONFLICT_REPAIR',
                             'ADDRESS_REMOTE_FEEDBACK')
                         AND ((turn.purpose = 'REMOTE_CI_REPAIR' AND EXISTS (
                                  SELECT 1 FROM ci_repair_operation operation
                                   WHERE operation.stage_turn_id = turn.id
                                     AND operation.operation_id = turn.operation_id
                                     AND operation.status = 'DISPATCHED'))
                           OR (turn.purpose = 'BRANCH_CONFLICT_REPAIR' AND EXISTS (
                                  SELECT 1 FROM branch_sync_dispatch_operation operation
                                   WHERE operation.stage_turn_id = turn.id
                                     AND operation.operation_id = turn.operation_id
                                     AND operation.status = 'DISPATCHED'))
                           OR (turn.purpose = 'ADDRESS_REMOTE_FEEDBACK' AND EXISTS (
                                  SELECT 1 FROM remote_feedback_stage_turn_request request
                                  JOIN remote_feedback_batch batch
                                    ON batch.id = request.remote_feedback_batch_id
                                   WHERE request.stage_turn_id = turn.id
                                     AND batch.status = 'ADDRESSING')))))
                   AND NOT EXISTS (
                       SELECT 1 FROM stage_turn later
                        WHERE later.stage_id = turn.stage_id
                          AND later.stage_generation = turn.stage_generation
                          AND later.rowid > turn.rowid)
                   AND NOT EXISTS (
                       SELECT 1 FROM stage_steering_request_v257 request
                        WHERE request.predecessor_operation_id = turn.operation_id
                          AND request.mode = 'CANCEL_AND_REPLACE'
                          AND request.status IN ('PENDING', 'ADMITTED'))
                   AND NOT EXISTS (
                       SELECT 1 FROM local_stage_turn_delivery_receipt receipt
                        WHERE receipt.operation_id = turn.operation_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM remote_runtime_delivery_receipt receipt
                        WHERE receipt.operation_id = turn.operation_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM ci_repair_delivery_receipt receipt
                        WHERE receipt.operation_id = turn.operation_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM branch_sync_delivery_receipt receipt
                        WHERE receipt.operation_id = turn.operation_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM remote_repair_steering_delivery_v257 receipt
                        WHERE receipt.operation_id = turn.operation_id)
                """, (rs, row) -> new StageTurnRecovery(
                        rs.getString("stage_turn_id"),
                        DispatchTicket.resultProtocolFailureDetail(
                                rs.getString("last_error"))),
                stage.taskId(), stage.id(),
                DispatchTicket.RESULT_PROTOCOL_FAILURE_PREFIX + "%");
        StageTurnRecovery replacement = replacements.size() == 1
                ? replacements.getFirst() : null;
        FailedStageTurnRecovery failure = null;
        if (tableAvailable("local_stage_turn_failure_v298")) {
            List<FailedStageTurnRecovery> failures = jdbc.query("""
                    SELECT failed.stage_turn_id, failed.blocker_id,
                           failed.error_message
                      FROM local_stage_turn_failure_v298 failed
                      JOIN stage_turn turn ON turn.id = failed.stage_turn_id
                      JOIN local_stage_turn_delivery_receipt delivery
                        ON delivery.stage_turn_id = turn.id
                       AND delivery.operation_id = turn.operation_id
                      JOIN dispatch_ticket ticket
                        ON ticket.operation_id = turn.operation_id
                       AND ticket.owner_kind = 'STAGE_TURN'
                       AND ticket.owner_id = turn.id
                       AND ticket.callback_route = 'STAGE_TURN_RESULT'
                      JOIN task_blocker blocker ON blocker.id = failed.blocker_id
                      JOIN stage owner ON owner.id = failed.stage_id
                      JOIN tasks task ON task.id = failed.task_id
                      JOIN task_current_stage current ON current.task_id = task.id
                     WHERE failed.task_id = ? AND failed.stage_id = ?
                       AND turn.status = 'FAILED'
                       AND delivery.acceptance = 'ACCEPTED'
                       AND delivery.raw_outcome IN ('FAILED', 'INDETERMINATE')
                       AND ticket.status = 'FAILED'
                       AND ticket.delivery_acceptance = 'ACCEPTED'
                       AND blocker.task_id = task.id
                       AND blocker.stage_id = owner.id
                       AND blocker.owner_kind = 'STAGE'
                       AND blocker.owner_id = owner.id
                       AND blocker.subject_revision = turn.id
                       AND blocker.blocker_type = 'OPERATION_FAILED'
                       AND blocker.status = 'OPEN'
                       AND owner.kind = 'LOCAL_DEVELOPMENT'
                       AND owner.generation = failed.stage_generation
                       AND owner.version = failed.cleared_stage_version
                       AND owner.completed_at_ms IS NULL
                       AND owner.end_reason IS NULL
                       AND task.workflow_version = 'V2'
                       AND task.lifecycle_state = 'ACTIVE'
                       AND task.epoch = turn.task_epoch
                       AND current.stage_id = owner.id
                       AND current.stage_generation = owner.generation
                       AND NOT EXISTS (
                           SELECT 1 FROM local_stage_turn_retry_v298 retry
                            WHERE retry.failure_id = failed.id
                               OR retry.blocker_id = failed.blocker_id
                               OR retry.predecessor_turn_id = turn.id)
                    """, (rs, row) -> new FailedStageTurnRecovery(
                            rs.getString("stage_turn_id"),
                            rs.getString("blocker_id"),
                            rs.getString("error_message")),
                    stage.taskId(), stage.id());
            failure = failures.size() == 1 ? failures.getFirst() : null;
        }
        List<LocalPublishBaseSyncRecovery> localPublishBaseSyncs = jdbc.query("""
                SELECT blocker.id, blocker.blocker_type,
                       CASE WHEN blocker.blocker_type =
                            'LOCAL_PUBLISH_BASE_SYNC_REQUIRED'
                            THEN NULL ELSE blocker.subject_revision END
                            AS episode_id,
                       json_extract(blocker.payload_json,
                           '$.sourceBaseSha') AS source_base_sha,
                       json_extract(blocker.payload_json,
                           '$.targetBaseSha') AS target_base_sha,
                       json_extract(blocker.payload_json,
                           '$.attemptNo') AS attempt_no,
                       json_extract(blocker.payload_json,
                           '$.attemptLimit') AS attempt_limit
                  FROM task_blocker blocker
                  JOIN tasks task ON task.id = blocker.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                 WHERE blocker.task_id = ? AND blocker.stage_id = ?
                   AND blocker.owner_kind = 'STAGE'
                   AND blocker.owner_id = owner.id
                   AND blocker.blocker_type IN (
                       'LOCAL_PUBLISH_BASE_SYNC_REQUIRED',
                       'LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED',
                       'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED')
                   AND blocker.status = 'OPEN'
                   AND json_valid(blocker.payload_json)
                   AND typeof(json_extract(blocker.payload_json,
                       '$.sourcePublishOperationId')) = 'text'
                   AND (blocker.blocker_type <>
                            'LOCAL_PUBLISH_BASE_SYNC_REQUIRED'
                     OR blocker.subject_revision = json_extract(
                            blocker.payload_json, '$.sourcePublishOperationId'))
                   AND typeof(json_extract(blocker.payload_json,
                       '$.sourceBaseSha')) = 'text'
                   AND length(trim(json_extract(blocker.payload_json,
                       '$.sourceBaseSha'))) > 0
                   AND typeof(json_extract(blocker.payload_json,
                       '$.targetBaseSha')) = 'text'
                   AND length(trim(json_extract(blocker.payload_json,
                       '$.targetBaseSha'))) > 0
                   AND json_extract(blocker.payload_json, '$.sourceBaseSha') <>
                       json_extract(blocker.payload_json, '$.targetBaseSha')
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND current.stage_id = blocker.stage_id
                   AND owner.task_id = task.id
                   AND owner.kind = 'LOCAL_DEVELOPMENT'
                   AND owner.generation = current.stage_generation
                   AND owner.checkpoint = 'LOCAL_REVIEW'
                   AND owner.completed_at_ms IS NULL
                   AND owner.end_reason IS NULL
                 ORDER BY blocker.opened_at_ms, blocker.id
                """, (rs, row) -> new LocalPublishBaseSyncRecovery(
                        rs.getString("id"), rs.getString("blocker_type"),
                        rs.getString("episode_id"),
                        rs.getString("source_base_sha"),
                        rs.getString("target_base_sha"),
                        nullableInt(rs, "attempt_no"),
                        nullableInt(rs, "attempt_limit"),
                        localBaseSyncMessage(rs.getString("blocker_type"))),
                stage.taskId(), stage.id());
        LocalPublishBaseSyncRecovery localPublishBaseSync =
                localPublishBaseSyncs.size() == 1
                        ? localPublishBaseSyncs.getFirst() : null;
        BranchSyncRecovery branchSync = jdbc.query("""
                SELECT episode.id, exhaustion.blocker_id,
                       exhaustion.reason, episode.attempt_count,
                       episode.attempt_limit
                  FROM branch_sync_exhaustion_v319 exhaustion
                  JOIN branch_sync_episode episode
                    ON episode.id = exhaustion.branch_sync_episode_id
                  JOIN task_blocker blocker
                    ON blocker.id = exhaustion.blocker_id
                  JOIN tasks task ON task.id = exhaustion.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN remote_development_stage remote
                    ON remote.stage_id = exhaustion.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                 WHERE exhaustion.task_id = ? AND exhaustion.stage_id = ?
                   AND episode.status = 'FAILED'
                   AND episode.task_id = task.id
                   AND episode.remote_development_stage_id = owner.id
                   AND episode.task_epoch = task.epoch
                   AND episode.stage_generation = owner.generation
                   AND blocker.task_id = task.id
                   AND blocker.stage_id = owner.id
                   AND blocker.owner_kind = 'EPISODE'
                   AND blocker.owner_id = episode.id
                   AND blocker.subject_revision = episode.id
                   AND blocker.blocker_type = 'BRANCH_SYNC_EXHAUSTED'
                   AND blocker.status = 'OPEN'
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND current.stage_id = owner.id
                   AND current.stage_generation = owner.generation
                   AND owner.kind = 'REMOTE_DEVELOPMENT'
                   AND owner.completed_at_ms IS NULL
                   AND remote.current_head_sha = exhaustion.remote_head_sha
                   AND remote.current_base_sha = exhaustion.remote_base_sha
                   AND code.code_fingerprint = exhaustion.code_fingerprint
                   AND code.head_sha = exhaustion.code_head_sha
                   AND code.base_sha = exhaustion.code_base_sha
                 ORDER BY exhaustion.exhausted_at_ms DESC, episode.id
                """, (rs, row) -> new BranchSyncRecovery(
                        rs.getString("id"), rs.getString("blocker_id"),
                        rs.getString("reason"), rs.getInt("attempt_count"),
                        rs.getInt("attempt_limit"),
                        List.of("MANUAL_TAKEOVER", "STOP_AUTOMATION")),
                stage.taskId(), stage.id()).stream().findFirst().orElse(null);
        WorktreeQuarantineRecovery worktreeQuarantine = jdbc.query("""
                SELECT quarantine.id, blocker.id AS blocker_id,
                       quarantine.source_operation_id, quarantine.reason,
                       task.epoch AS task_epoch, owner.id AS stage_id,
                       owner.generation AS stage_generation,
                       quarantine.worktree_path,
                       quarantine.expected_branch_name,
                       quarantine.expected_code_fingerprint,
                       quarantine.expected_head_sha,
                       code.base_sha AS expected_base_sha,
                       repair.id AS repair_operation_id,
                       repair.status AS repair_status
                  FROM agent_turn_worktree_quarantine_v318 quarantine
                  JOIN stage_turn turn
                    ON turn.operation_id = quarantine.source_operation_id
                  JOIN task_blocker blocker
                    ON blocker.subject_revision = quarantine.id
                   AND blocker.owner_kind = 'OPERATION'
                   AND blocker.owner_id = quarantine.source_operation_id
                  JOIN tasks task ON task.id = quarantine.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  JOIN task_code_identity identity ON identity.task_id = task.id
                  LEFT JOIN worktree_quarantine_repair_operation_v318 repair
                    ON repair.id = (
                        SELECT candidate.id
                          FROM worktree_quarantine_repair_operation_v318 candidate
                         WHERE candidate.quarantine_id = quarantine.id
                         ORDER BY candidate.requested_at_ms DESC,
                                  candidate.attempt DESC
                         LIMIT 1)
                 WHERE quarantine.task_id = ? AND owner.id = ?
                   AND quarantine.status = 'OPEN'
                   AND blocker.task_id = task.id
                   AND blocker.stage_id = quarantine.stage_id
                   AND blocker.blocker_type =
                       'WORKTREE_RESTORE_QUARANTINED'
                   AND blocker.status = 'OPEN'
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND current.stage_id = owner.id
                   AND current.stage_generation = owner.generation
                   AND turn.stage_id = quarantine.stage_id
                   AND code.code_fingerprint =
                       quarantine.expected_code_fingerprint
                   AND code.head_sha = quarantine.expected_head_sha
                   AND identity.worktree_path = quarantine.worktree_path
                   AND identity.branch_name = quarantine.expected_branch_name
                   AND owner.completed_at_ms IS NULL
                 ORDER BY quarantine.opened_at_ms DESC, quarantine.id
                """, (rs, row) -> {
                    String repairStatus = rs.getString("repair_status");
                    boolean live = "REQUESTED".equals(repairStatus)
                            || "DISPATCHED".equals(repairStatus);
                    return new WorktreeQuarantineRecovery(
                            rs.getString("id"), rs.getString("blocker_id"),
                            rs.getString("source_operation_id"),
                            rs.getLong("task_epoch"),
                            rs.getString("stage_id"),
                            rs.getLong("stage_generation"),
                            rs.getString("worktree_path"),
                            rs.getString("expected_branch_name"),
                            rs.getString("expected_code_fingerprint"),
                            rs.getString("expected_head_sha"),
                            rs.getString("expected_base_sha"),
                            rs.getString("repair_operation_id"), repairStatus,
                            live ? "Worktree repair is queued or running"
                                    : rs.getString("reason"),
                            live ? List.of() : List.of("REPAIR_WORKTREE"));
                },
                stage.taskId(), stage.id()).stream().findFirst().orElse(null);
        return new RecoveryOptions(
                ci, cleanup, replacement, failure, localPublishBaseSync,
                branchSync, worktreeQuarantine);
    }

    /** Cancels only live AgentTurn tickets for the exact current Stage fence. */
    public void interrupt(String taskId, String stageId)
    {
        requireStage(taskId, stageId);
        List<String> exact = jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN tasks task ON task.id = ticket.task_id
                JOIN stage owner ON owner.id = ticket.stage_id
                JOIN task_current_stage current ON current.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND owner.id = ? AND owner.task_id = task.id
                  AND owner.completed_at_ms IS NULL
                  AND task.epoch = ticket.task_epoch
                  AND owner.generation = ticket.stage_generation
                  AND current.stage_id = owner.id
                  AND current.stage_generation = owner.generation
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.status IN (
                      'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'RESULT_PENDING', 'CLAIMED', 'RUNNING')
                ORDER BY ticket.created_at_ms, ticket.id
                """, (rs, row) -> rs.getString("id"), taskId, stageId);
        exact.forEach(tickets::requestCancel);
    }

    /**
     * Streams newly committed V2 execution evidence. One virtual thread waits
     * per open SSE connection; no legacy runtime is touched.
     */
    public Runnable subscribe(
            String taskId, String stageId, Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        requireStage(taskId, stageId);
        AtomicBoolean stopped = new AtomicBoolean();
        long cursor = latestLogRow(taskId, stageId);
        Thread worker = Thread.startVirtualThread(
                () -> stream(taskId, stageId, cursor, listener, stopped));
        return () -> {
            if (stopped.compareAndSet(false, true)) {
                worker.interrupt();
            }
        };
    }

    private void stream(
            String taskId,
            String stageId,
            long initialCursor,
            Consumer<StreamEvent> listener,
            AtomicBoolean stopped)
    {
        long cursor = initialCursor;
        try {
            while (!stopped.get()) {
                List<LogFacts> rows = logsAfter(taskId, stageId, cursor);
                for (LogFacts row : rows) {
                    cursor = row.rowId();
                    for (StreamEvent event : events(row)) {
                        listener.accept(event);
                    }
                }
                Thread.sleep(STREAM_POLL_MS);
            }
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException ignored) {
            // The controller closes the emitter independently; a stale or
            // disconnected subscriber must not affect the durable execution.
        }
    }

    private List<StreamEvent> events(LogFacts row)
    {
        try {
            JsonNode payload = json.readTree(row.payload());
            if ("provider".equals(payload.path("stream").asText())) {
                String line = payload.path("line").asText("");
                CliStreamParser parser = cliParser(row.provider(), line);
                return parser.parse(line, instant(row.createdAtMs()));
            }
            Instant at = instant(row.createdAtMs());
            return switch (payload.path("event").asText()) {
                case "text_delta" -> List.of(new StreamEvent.AssistantTextDelta(
                        at, payload.path("blockIndex").asInt(),
                        payload.path("chunk").asText("")));
                case "tool_started" -> List.of(new StreamEvent.ToolCallStarted(
                        at, payload.path("callId").asText(""),
                        payload.path("tool").asText(""),
                        payload.path("input").asText("")));
                case "tool_finished" -> List.of(new StreamEvent.ToolCallDone(
                        at, payload.path("callId").asText(""),
                        payload.path("result").asText(""),
                        payload.path("isError").asBoolean()));
                default -> List.of();
            };
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private CliStreamParser cliParser(String provider, String line)
    {
        String normalized = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (normalized.contains("codex") || line.contains("\"thread.started\"")
                || line.contains("\"turn.completed\"")) {
            return new CodexJsonParser(json);
        }
        return new StreamJsonParser(json);
    }

    private long latestLogRow(String taskId, String stageId)
    {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(log.rowid), 0)
                FROM agent_execution_log log
                JOIN agent_execution execution ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                JOIN tasks task ON task.id = ticket.task_id
                JOIN stage owner ON owner.id = ticket.stage_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.epoch = ticket.task_epoch
                  AND owner.id = ? AND owner.task_id = task.id
                  AND ticket.stage_generation = owner.generation
                """, Long.class, taskId, stageId);
        return value == null ? 0 : value;
    }

    private List<LogFacts> logsAfter(String taskId, String stageId, long cursor)
    {
        return jdbc.query("""
                SELECT log.rowid AS row_id, log.payload, log.created_at_ms,
                       execution.provider
                FROM agent_execution_log log
                JOIN agent_execution execution ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                JOIN tasks task ON task.id = ticket.task_id
                JOIN stage owner ON owner.id = ticket.stage_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.epoch = ticket.task_epoch
                  AND owner.id = ? AND owner.task_id = task.id
                  AND ticket.stage_generation = owner.generation
                  AND log.rowid > ?
                ORDER BY log.rowid
                LIMIT 100
                """, (rs, row) -> new LogFacts(
                        rs.getLong("row_id"), rs.getString("payload"),
                        rs.getLong("created_at_ms"), rs.getString("provider")),
                taskId, stageId, cursor);
    }

    private RunFacts requireRun(String runId)
    {
        String ticketId = V2AgentRunProjection.ticketId(runId);
        List<RunFacts> rows = jdbc.query("""
                SELECT ticket.id AS ticket_id, ticket.owner_kind,
                       ticket.owner_id,
                       COALESCE(ticket.task_id, task_owned.task_id,
                           stage_owner.task_id) AS task_id,
                       COALESCE(ticket.stage_id,
                           task_owned.trigger_stage_id,
                           stage_owned.stage_id) AS stage_id,
                       COALESCE(task_owned.id, stage_owned.id) AS turn_id,
                       COALESCE(task_owned.purpose,
                           stage_owned.purpose) AS purpose,
                       COALESCE(task_owned.status,
                           stage_owned.status) AS turn_status,
                       COALESCE(task_owned.launch_input,
                           stage_owned.launch_input) AS launch_input,
                       COALESCE(task_owned.requested_at_ms,
                           stage_owned.requested_at_ms) AS requested_at_ms,
                       COALESCE(task_owned.started_at_ms,
                           stage_owned.started_at_ms) AS started_at_ms,
                       COALESCE(task_owned.finished_at_ms,
                           stage_owned.finished_at_ms) AS finished_at_ms,
                       COALESCE(task_owned.error_message,
                           stage_owned.error_message) AS error_message,
                       CASE WHEN ticket.status IN (
                           'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                           'RESULT_PENDING', 'CLAIMED', 'RUNNING',
                           'DELIVERING') THEN 1 ELSE 0 END AS live
                FROM dispatch_ticket ticket
                LEFT JOIN task_turn task_owned
                  ON ticket.owner_kind = 'TASK_TURN'
                 AND task_owned.id = ticket.owner_id
                 AND task_owned.operation_id = ticket.operation_id
                LEFT JOIN stage_turn stage_owned
                  ON ticket.owner_kind = 'STAGE_TURN'
                 AND stage_owned.id = ticket.owner_id
                 AND stage_owned.operation_id = ticket.operation_id
                LEFT JOIN stage stage_owner
                  ON stage_owner.id = stage_owned.stage_id
                LEFT JOIN tasks task ON task.id = COALESCE(
                    ticket.task_id, task_owned.task_id, stage_owner.task_id)
                WHERE ticket.id = ?
                  AND ticket.async_family = 'AGENT_TURN'
                  AND task.workflow_version = 'V2'
                  AND (task_owned.id IS NOT NULL
                    OR stage_owned.id IS NOT NULL)
                """, (rs, row) -> new RunFacts(
                        rs.getString("ticket_id"),
                        rs.getString("owner_kind"),
                        rs.getString("owner_id"),
                        rs.getString("task_id"),
                        rs.getString("stage_id"),
                        new TurnFacts(
                                rs.getString("turn_id"),
                                rs.getString("purpose"),
                                rs.getString("turn_status"),
                                rs.getString("launch_input"),
                                rs.getLong("requested_at_ms"),
                                nullableLong(rs, "started_at_ms"),
                                nullableLong(rs, "finished_at_ms"),
                                rs.getString("error_message")),
                        rs.getBoolean("live")), ticketId);
        if (rows.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "run has no task/stage-backed V2 log: " + runId);
        }
        RunFacts run = rows.getFirst();
        if (run.stageId() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "run has no stage-backed V2 log: " + runId);
        }
        return run;
    }

    private StageFacts requireStage(String taskId, String stageId)
    {
        List<StageFacts> rows = jdbc.query("""
                SELECT owner.id, owner.task_id, owner.kind, owner.generation,
                       owner.checkpoint, owner.opened_at_ms, owner.completed_at_ms,
                       owner.end_reason, task.seq, task.name,
                       identity.branch_name, context.repository_id,
                       CASE WHEN json_valid(context.work_model_snapshot)
                            THEN json_extract(context.work_model_snapshot, '$.kind')
                            ELSE NULL END AS runtime,
                       brain.model, binding.remote_pr_number,
                       COALESCE(snapshot.pr_state, remote_pr.status) AS pr_state,
                       current_owner.kind AS current_kind,
                       current_owner.checkpoint AS current_checkpoint
                FROM stage owner
                JOIN tasks task ON task.id = owner.task_id
                LEFT JOIN task_current_stage pointer ON pointer.task_id = task.id
                LEFT JOIN stage current_owner ON current_owner.id = pointer.stage_id
                LEFT JOIN task_code_identity identity ON identity.task_id = task.id
                LEFT JOIN task_creation_context context ON context.task_id = task.id
                LEFT JOIN task_brain brain ON brain.task_id = task.id
                LEFT JOIN remote_pr_binding binding ON binding.task_id = task.id
                LEFT JOIN remote_development_stage remote
                  ON remote.task_id = task.id
                LEFT JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                LEFT JOIN pr remote_pr ON remote_pr.task_id = task.id
                  AND remote_pr.origin = 'task'
                WHERE owner.id = ? AND owner.task_id = ?
                  AND task.workflow_version = 'V2'
                ORDER BY remote.subject_changed_at_ms DESC
                LIMIT 1
                """, V2StageApiService::stageFacts, stageId, taskId);
        if (rows.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "no V2 stage: " + stageId);
        }
        return rows.getFirst();
    }

    private boolean activeAgent(String taskId, StageFacts stage)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket ticket
                JOIN tasks task ON task.id = ticket.task_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.epoch = ticket.task_epoch
                  AND ticket.stage_id = ? AND ticket.stage_generation = ?
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.status IN (
                      'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'RESULT_PENDING', 'CLAIMED', 'RUNNING')
                """, Integer.class, taskId, stage.id(), stage.generation());
        return count != null && count > 0;
    }

    private List<TurnFacts> turns(String stageId)
    {
        return jdbc.query("""
                SELECT id, purpose, status, launch_input, requested_at_ms,
                       started_at_ms, finished_at_ms, error_message
                FROM stage_turn
                WHERE stage_id = ?
                ORDER BY requested_at_ms, id
                """, (rs, row) -> new TurnFacts(
                        rs.getString("id"), rs.getString("purpose"),
                        rs.getString("status"), rs.getString("launch_input"),
                        rs.getLong("requested_at_ms"),
                        nullableLong(rs, "started_at_ms"),
                        nullableLong(rs, "finished_at_ms"),
                        rs.getString("error_message")), stageId);
    }

    private List<ConversationRow> conversation(
            String stageId, List<TurnFacts> turns)
    {
        List<ConversationRow> rows = new ArrayList<>();
        for (TurnFacts turn : turns) {
            rows.add(conversationRow(
                    "turn-user-" + turn.id(), "user", prompt(turn.launchInput()),
                    turn.requestedAtMs()));
        }
        rows.addAll(jdbc.query("""
                SELECT message.id, message.role, message.body,
                       message.created_at_ms
                FROM stage_message message
                JOIN stage_turn turn ON turn.id = message.turn_id
                WHERE turn.stage_id = ?
                ORDER BY message.created_at_ms, message.turn_id, message.seq
                """, (rs, row) -> conversationRow(
                        rs.getString("id"), role(rs.getString("role")),
                        rs.getString("body"), rs.getLong("created_at_ms")),
                stageId));
        rows.addAll(jdbc.query("""
                SELECT execution.id, execution.raw_result,
                       execution.finished_at_ms
                FROM agent_execution execution
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                WHERE ticket.stage_id = ?
                  AND execution.raw_result IS NOT NULL
                  AND execution.finished_at_ms IS NOT NULL
                ORDER BY execution.finished_at_ms, execution.id
                """, (rs, row) -> {
                    String body = finalText(rs.getString("raw_result"));
                    return body.isBlank() ? null : conversationRow(
                            "execution-assistant-" + rs.getString("id"),
                            "agent", body, rs.getLong("finished_at_ms"));
                }, stageId).stream().filter(row -> row != null).toList());
        rows.addAll(toolCalls(stageId));
        rows.sort(Comparator.comparing(ConversationRow::ts)
                .thenComparing(ConversationRow::id));
        return List.copyOf(rows);
    }

    /**
     * Replays the durable provider frames of this Stage's executions as tool
     * rows. The live stream clears its own compact activity list on TurnDone,
     * so without this replay a finished Stage lost every tool call it had just
     * shown and kept only its prompt and final result.
     */
    private List<ConversationRow> toolCalls(String stageId)
    {
        return jdbc.query("""
                SELECT log.execution_id, log.seq, log.created_at_ms,
                       CASE WHEN json_valid(log.payload)
                            THEN json_extract(log.payload, '$.line')
                            END AS line
                FROM agent_execution_log log
                JOIN agent_execution execution
                  ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                WHERE ticket.stage_id = ?
                ORDER BY log.created_at_ms, log.execution_id, log.seq
                """, (rs, row) -> toolRows(
                        rs.getString("execution_id"), rs.getLong("seq"),
                        rs.getString("line"), rs.getLong("created_at_ms")),
                stageId).stream().flatMap(List::stream).toList();
    }

    /** One row per {@code tool_use} block in an assistant frame; other frame
     *  kinds (deltas, system, results) carry no tool evidence. */
    private List<ConversationRow> toolRows(
            String executionId, long seq, String line, long at)
    {
        if (line == null) {
            // Not every log row is a JSON provider frame; older raw traces are
            // plain strings and carry no tool evidence.
            return List.of();
        }
        StreamLine parsed;
        try {
            parsed = json.readValue(line, StreamLine.class);
        }
        catch (JacksonException ignored) {
            // A frame we cannot type is dropped, exactly as the live parser
            // drops it; it must never break the transcript read.
            return List.of();
        }
        if (!(parsed instanceof StreamLine.Assistant assistant)
                || assistant.message() == null) {
            return List.of();
        }
        List<ConversationRow> rows = new ArrayList<>();
        for (StreamLine.ContentBlock block : assistant.message().content()) {
            if (block instanceof StreamLine.ContentBlock.ToolUse use) {
                rows.add(new ConversationRow(
                        "tool-" + executionId + "-" + seq + "-" + use.id(),
                        null, "tool_call", null, use.name(), use.name(),
                        toolDetail(use.input()), null, null, null, null,
                        instant(at).toString(), null,
                        List.of(), List.of(), null));
            }
        }
        return rows;
    }

    /** The one argument worth showing beside a tool name — the command or the
     *  path it acted on, matching the live activity row's own choice. */
    private static String toolDetail(JsonNode input)
    {
        if (input == null) {
            return null;
        }
        for (String field : List.of(
                "pattern", "query", "command", "path", "file_path", "text")) {
            JsonNode value = input.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private List<ConversationRow> conversation(RunFacts run)
    {
        TurnFacts turn = run.turn();
        List<ConversationRow> rows = new ArrayList<>();
        rows.add(conversationRow(
                "turn-user-" + turn.id(), "user", prompt(turn.launchInput()),
                turn.requestedAtMs()));
        String messageTable = switch (run.ownerKind()) {
            case "TASK_TURN" -> "task_message";
            case "STAGE_TURN" -> "stage_message";
            default -> throw new IllegalArgumentException(
                    "unsupported V2 run owner: " + run.ownerKind());
        };
        rows.addAll(jdbc.query("""
                SELECT message.id, message.role, message.body,
                       message.created_at_ms
                FROM %s message
                WHERE message.turn_id = ?
                ORDER BY message.created_at_ms, message.seq
                """.formatted(messageTable), (rs, row) -> conversationRow(
                        rs.getString("id"), role(rs.getString("role")),
                        rs.getString("body"), rs.getLong("created_at_ms")),
                run.ownerId()));
        rows.addAll(jdbc.query("""
                SELECT execution.id, execution.raw_result,
                       execution.finished_at_ms
                FROM agent_execution execution
                WHERE execution.ticket_id = ?
                  AND execution.raw_result IS NOT NULL
                  AND execution.finished_at_ms IS NOT NULL
                ORDER BY execution.finished_at_ms, execution.id
                """, (rs, row) -> {
                    String body = finalText(rs.getString("raw_result"));
                    return body.isBlank() ? null : conversationRow(
                            "execution-assistant-" + rs.getString("id"),
                            "agent", body, rs.getLong("finished_at_ms"));
                }, run.ticketId()).stream()
                .filter(row -> row != null)
                .toList());
        rows.sort(Comparator.comparing(ConversationRow::ts)
                .thenComparing(ConversationRow::id));
        return List.copyOf(rows);
    }

    private String prompt(String launchInput)
    {
        try {
            JsonNode value = json.readTree(launchInput);
            return value.path("prompt").asText(launchInput);
        }
        catch (Exception ignored) {
            return launchInput;
        }
    }

    private String finalText(String rawResult)
    {
        try {
            JsonNode result = json.readTree(rawResult);
            String payload = result.path("payloadJson").asText();
            if (payload.isBlank()) {
                return "";
            }
            return json.readTree(payload).path("finalText").asText("");
        }
        catch (Exception ignored) {
            return "";
        }
    }

    private static ConversationRow conversationRow(
            String id, String kind, String body, long at)
    {
        return new ConversationRow(
                id, null, kind, body, null, null, null, null, null, null,
                null, instant(at).toString(), null, List.of(), List.of(), null);
    }

    private static List<IterationDetail> iterations(List<TurnFacts> turns)
    {
        List<IterationDetail> result = new ArrayList<>(turns.size());
        for (int index = 0; index < turns.size(); index++) {
            TurnFacts turn = turns.get(index);
            result.add(new IterationDetail(
                    turn.id(), index + 1, turn.purpose(),
                    instant(turn.startedAtMs() == null
                            ? turn.requestedAtMs() : turn.startedAtMs()).toString(),
                    turn.finishedAtMs() == null ? null
                            : instant(turn.finishedAtMs()).toString(),
                    turn.finishedAtMs() == null ? null : turn.status(),
                    turn.error(), null, List.of()));
        }
        return List.copyOf(result);
    }

    private Usage usage(String stageId)
    {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(execution.tokens_in + execution.tokens_out), 0)
                           AS tokens,
                       COALESCE(SUM(execution.cost_usd_milli), 0) AS cost,
                       (SELECT COUNT(*)
                        FROM agent_execution_log log
                        JOIN agent_execution logged ON logged.id = log.execution_id
                        JOIN dispatch_ticket logged_ticket
                          ON logged_ticket.id = logged.ticket_id
                        WHERE logged_ticket.stage_id = ?
                          AND json_valid(log.payload)
                          AND json_extract(log.payload, '$.event') = 'tool_started')
                           AS tool_calls
                FROM agent_execution execution
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                WHERE ticket.stage_id = ?
                """, (rs, row) -> new Usage(
                        rs.getLong("tokens"), rs.getLong("cost"),
                        rs.getInt("tool_calls")), stageId, stageId);
    }

    private Usage runUsage(String ticketId)
    {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                           execution.tokens_in + execution.tokens_out), 0)
                           AS tokens,
                       COALESCE(SUM(execution.cost_usd_milli), 0) AS cost,
                       (SELECT COUNT(*)
                        FROM agent_execution_log log
                        JOIN agent_execution logged
                          ON logged.id = log.execution_id
                        WHERE logged.ticket_id = ?
                          AND json_valid(log.payload)
                          AND json_extract(log.payload, '$.event') =
                              'tool_started') AS tool_calls
                FROM agent_execution execution
                WHERE execution.ticket_id = ?
                """, (rs, row) -> new Usage(
                        rs.getLong("tokens"), rs.getLong("cost"),
                        rs.getInt("tool_calls")), ticketId, ticketId);
    }

    private static StageFacts stageFacts(ResultSet rs, int row)
            throws SQLException
    {
        String name = rs.getString("name");
        String branch = rs.getString("branch_name");
        String prState = rs.getString("pr_state");
        return new StageFacts(
                rs.getString("id"), rs.getString("task_id"),
                rs.getString("kind"), rs.getLong("generation"),
                rs.getString("checkpoint"), rs.getLong("opened_at_ms"),
                nullableLong(rs, "completed_at_ms"), rs.getString("end_reason"),
                rs.getLong("seq"), name == null || name.isBlank() ? text(branch) : name,
                branch, rs.getString("repository_id"), rs.getString("runtime"),
                rs.getString("model"), nullableInt(rs, "remote_pr_number"),
                prState != null && prState.toUpperCase(Locale.ROOT).contains("DRAFT"),
                rs.getString("current_kind"), rs.getString("current_checkpoint"));
    }

    private static Integer currentIteration(List<TurnFacts> turns)
    {
        for (int index = turns.size() - 1; index >= 0; index--) {
            if (turns.get(index).finishedAtMs() == null) {
                return index + 1;
            }
        }
        return null;
    }

    private static String legacyStageType(String kind)
    {
        return switch (kind) {
            case "PLAN" -> "PLAN_STAGE";
            case "LOCAL_DEVELOPMENT" -> "DEVELOPMENT_STAGE";
            case "REMOTE_DEVELOPMENT" -> "REMOTE_DEVELOPMENT_STAGE";
            case "CLEANUP" -> "CLEANUP_STAGE";
            default -> throw new IllegalArgumentException("unknown V2 Stage kind: " + kind);
        };
    }

    private static String legacyPhase(String kind, String checkpoint)
    {
        // Mirrors V2DevelopmentFlowProjection#phase: a COMPLETED checkpoint is
        // not a live phase, so it must never fall through to the IMPLEMENTING
        // default below.
        if (kind == null || checkpoint == null || "COMPLETED".equals(checkpoint)) {
            return "CLEANUP".equals(kind) && "COMPLETED".equals(checkpoint)
                    ? "COMPLETED" : null;
        }
        if ("PLAN".equals(kind)) {
            return "PLANNING";
        }
        if ("REMOTE_DEVELOPMENT".equals(kind)) {
            return switch (checkpoint) {
                case "WAITING_CI", "ADDRESSING_REMOTE_FEEDBACK" ->
                        "PUSHED_AWAITING_CI";
                case "AWAITING_READY" -> "AWAITING_READY";
                default -> "AWAITING_REMOTE_REVIEW";
            };
        }
        if ("CLEANUP".equals(kind)) {
            return "COMPLETED";
        }
        return switch (checkpoint) {
            case "VALIDATING" -> "VALIDATING";
            case "BRAIN_REVIEW" -> "INTERNAL_REVIEW";
            case "LOCAL_REVIEW", "PUBLISHING" -> "AWAITING_PUSH";
            case "ADDRESSING_LOCAL_FEEDBACK" -> "ADDRESSING_LOCAL_COMMENTS";
            default -> "IMPLEMENTING";
        };
    }

    private static String runtime(String value)
    {
        return "CLI".equalsIgnoreCase(value) ? "CLI" : "API";
    }

    private static String role(String value)
    {
        return "assistant".equalsIgnoreCase(value) ? "agent" : "user";
    }

    private static String terminalState(StageFacts stage)
    {
        if (stage.completedAtMs() == null) {
            return null;
        }
        return switch (text(stage.endReason())) {
            case "TASK_CANCELED", "SUPERSEDED_BY_REPLAN" -> "aborted";
            default -> "succeeded";
        };
    }

    private static String contextBand(long tokens)
    {
        if (tokens >= CONTEXT_TOKEN_LIMIT * 9L / 10L) {
            return "danger";
        }
        if (tokens >= CONTEXT_TOKEN_LIMIT * 3L / 4L) {
            return "warn";
        }
        return "safe";
    }

    private static Instant instant(long value)
    {
        return Instant.ofEpochMilli(value);
    }

    private static String text(String value)
    {
        return value == null ? "" : value;
    }

    private boolean tableAvailable(String table)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                 WHERE type = 'table' AND name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String localBaseSyncMessage(String blockerType)
    {
        return switch (blockerType) {
            case "LOCAL_PUBLISH_BASE_SYNC_REQUIRED" ->
                    "The remote base moved before the first push";
            case "LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED" ->
                    "The approved base sync failed; approve one more attempt";
            case "LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED" ->
                    "Local base-sync attempts are exhausted; extend by one attempt";
            default -> throw new IllegalArgumentException(
                    "Unsupported local base-sync blocker: " + blockerType);
        };
    }

    private record StageFacts(
            String id, String taskId, String kind, long generation,
            String checkpoint, long openedAtMs, Long completedAtMs,
            String endReason, long taskNumber, String title, String branch,
            String repositoryId, String runtime, String model,
            Integer prNumber, boolean prDraft,
            String currentKind, String currentCheckpoint) {}

    private record TurnFacts(
            String id, String purpose, String status, String launchInput,
            long requestedAtMs, Long startedAtMs, Long finishedAtMs,
            String error) {}

    private record Usage(long tokens, long costUsdMilli, int toolCalls) {}

    private record RunFacts(
            String ticketId, String ownerKind, String ownerId,
            String taskId, String stageId, TurnFacts turn, boolean live) {}

    private record LogFacts(
            long rowId, String payload, long createdAtMs, String provider) {}
}
