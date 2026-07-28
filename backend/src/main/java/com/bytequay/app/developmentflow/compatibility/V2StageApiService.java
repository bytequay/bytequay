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
import com.bytequay.app.beans.stage.StageDetailData.ConversationRow;
import com.bytequay.app.beans.stage.StageDetailData.DetailTask;
import com.bytequay.app.beans.stage.StageDetailData.IterationDetail;
import com.bytequay.app.beans.stage.StageDetailData.Scrubber;
import com.bytequay.app.beans.stage.StageDetailData.StageConfig;
import com.bytequay.app.beans.stage.StageDetailData.StageInfo;
import com.bytequay.app.beans.stage.StageDetailData.StageMetricsSubset;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.StreamJsonParser;
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
                        legacyPhase(stage.kind(), stage.checkpoint()),
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
                List.of());
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
     * per open SSE connection; no AgentScheduler or legacy runtime is touched.
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
                       COALESCE(snapshot.pr_state, remote_pr.status) AS pr_state
                FROM stage owner
                JOIN tasks task ON task.id = owner.task_id
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
        rows.sort(Comparator.comparing(ConversationRow::ts)
                .thenComparing(ConversationRow::id));
        return List.copyOf(rows);
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
                prState != null && prState.toUpperCase(Locale.ROOT).contains("DRAFT"));
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

    private record StageFacts(
            String id, String taskId, String kind, long generation,
            String checkpoint, long openedAtMs, Long completedAtMs,
            String endReason, long taskNumber, String title, String branch,
            String repositoryId, String runtime, String model,
            Integer prNumber, boolean prDraft) {}

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
