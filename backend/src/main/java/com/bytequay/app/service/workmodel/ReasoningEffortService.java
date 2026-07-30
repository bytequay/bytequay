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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.sqlite.WorkModelJson;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Resolves the mutable half of a V2 work model.
 *
 * <p>The engine snapshot remains immutable. A scope may only override its
 * provider-native reasoning effort, and that value is overlaid when the owner
 * creates a new durable Turn. Launch input already persisted for a queued or
 * running Turn is never rewritten.
 */
@Component
public class ReasoningEffortService
{
    private static final Set<String> VALUES = Set.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskCommandExecutor commands;

    public ReasoningEffortService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TaskCommandExecutor commands)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.commands = requireNonNull(commands, "commands is null");
    }

    public WorkModel forTrunk(String trunkId, WorkModel frozenEngine)
    {
        requireText(trunkId, "trunkId");
        WorkModel engine = requireNonNull(frozenEngine, "frozenEngine is null");
        List<String> rows = jdbc.query("""
                SELECT json_extract(work_model_json, '$.reasoningEffort')
                FROM threads
                WHERE id = ? AND turn_version = 'V2'
                """, (rs, row) -> rs.getString(1), trunkId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("no typed Trunk: " + trunkId);
        }
        return withEffort(engine, first(rows.getFirst(), engine.reasoningEffort()));
    }

    public WorkModel forTask(
            String trunkId, String taskId, WorkModel frozenEngine)
    {
        return resolve(trunkId, taskId, null, frozenEngine);
    }

    public WorkModel forStage(
            String trunkId, String taskId, String stageId,
            WorkModel frozenEngine)
    {
        requireText(stageId, "stageId");
        return resolve(trunkId, taskId, stageId, frozenEngine);
    }

    /** The frozen engine plus the exact stage override, for REST responses. */
    public WorkModel stageOverride(
            String trunkId, String taskId, String stageId,
            WorkModel frozenEngine)
    {
        ScopeEfforts efforts = efforts(trunkId, taskId, stageId);
        return efforts.stage() == null
                ? null : withEffort(frozenEngine, efforts.stage());
    }

    /** The actual frozen Task engine, with the current effort cascade. */
    public WorkModel resolveTaskEngine(String trunkId, String taskId)
    {
        return forTask(trunkId, taskId, frozenTaskEngine(trunkId, taskId));
    }

    /** The actual frozen Stage engine, with stage → task → trunk effort. */
    public WorkModel resolveStageEngine(
            String trunkId, String taskId, String stageId)
    {
        return forStage(
                trunkId, taskId, stageId, frozenTaskEngine(trunkId, taskId));
    }

    /** Set or clear the V2 Trunk override on the Trunk command stripe. */
    public void setTrunk(String trunkId, WorkModel frozenEngine, String effort)
    {
        requireText(trunkId, "trunkId");
        commands.executeVoid("v2-trunk/" + trunkId, () -> {
            TaskCommandExecutor.requireCurrent("v2-trunk/" + trunkId);
            int updated = jdbc.update("""
                    UPDATE threads SET work_model_json = ?
                    WHERE id = ? AND turn_version = 'V2'
                    """, overrideJson(frozenEngine, effort), trunkId);
            requireUpdated(updated, "Trunk", trunkId);
        });
    }

    /** Set or clear the V2 Task override on the Task command stripe. */
    public void setTask(
            String trunkId,
            String taskId,
            WorkModel frozenEngine,
            String effort)
    {
        requireText(trunkId, "trunkId");
        requireText(taskId, "taskId");
        commands.executeVoid(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            int updated = jdbc.update("""
                    UPDATE tasks SET work_model_json = ?
                    WHERE id = ? AND thread_id = ?
                      AND workflow_version = 'V2'
                    """, overrideJson(frozenEngine, effort), taskId, trunkId);
            requireUpdated(updated, "Task", taskId);
        });
    }

    /**
     * Set or clear one exact current, open V2 Stage override on its Task
     * command stripe. Sealed or superseded Stages are immutable, like their
     * already-admitted Turns.
     */
    public void setStage(String taskId, String stageId, String effort)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        String normalized = normalize(effort);
        commands.executeVoid(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            int updated = jdbc.update("""
                    UPDATE stage
                    SET reasoning_effort = ?
                    WHERE id = ? AND task_id = ? AND completed_at_ms IS NULL
                      AND EXISTS (
                          SELECT 1
                          FROM task_current_stage current
                          JOIN tasks task ON task.id = current.task_id
                          WHERE current.task_id = stage.task_id
                            AND current.stage_id = stage.id
                            AND current.stage_generation = stage.generation
                            AND task.workflow_version = 'V2')
                    """, normalized, stageId, taskId);
            requireUpdated(updated, "current open Stage", stageId);
        });
    }

    public static String requested(WorkModel model)
    {
        return normalize(model == null ? null : model.reasoningEffort());
    }

    private String overrideJson(WorkModel frozenEngine, String effort)
    {
        String normalized = normalize(effort);
        if (normalized == null) {
            return null;
        }
        return WorkModelJson.serialise(
                mapper,
                withEffort(requireNonNull(frozenEngine, "frozenEngine is null"), normalized));
    }

    private static void requireUpdated(int updated, String owner, String id)
    {
        if (updated != 1) {
            throw new IllegalStateException(
                    "V2 " + owner + " is unavailable: " + id);
        }
    }

    private WorkModel resolve(
            String trunkId, String taskId, String stageId,
            WorkModel frozenEngine)
    {
        WorkModel engine = requireNonNull(frozenEngine, "frozenEngine is null");
        ScopeEfforts efforts = efforts(trunkId, taskId, stageId);
        return withEffort(engine, first(
                efforts.stage(), efforts.task(), efforts.trunk(),
                engine.reasoningEffort()));
    }

    private ScopeEfforts efforts(
            String trunkId, String taskId, String stageId)
    {
        requireText(trunkId, "trunkId");
        requireText(taskId, "taskId");
        List<ScopeEfforts> rows = jdbc.query("""
                SELECT owner.reasoning_effort AS stage_effort,
                       json_extract(task.work_model_json,
                           '$.reasoningEffort') AS task_effort,
                       json_extract(trunk.work_model_json,
                           '$.reasoningEffort') AS trunk_effort
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                LEFT JOIN stage owner
                  ON owner.id = ? AND owner.task_id = task.id
                WHERE task.id = ? AND task.thread_id = ?
                  AND task.workflow_version = 'V2'
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> new ScopeEfforts(
                rs.getString("stage_effort"),
                rs.getString("task_effort"),
                rs.getString("trunk_effort")),
                stageId, taskId, trunkId);
        if (rows.size() != 1
                || (stageId != null && rows.getFirst().stage() == null
                && !stageExists(taskId, stageId))) {
            throw new IllegalArgumentException(
                    stageId == null
                            ? "no typed Task: " + taskId
                            : "no typed Stage: " + stageId);
        }
        return rows.getFirst();
    }

    private boolean stageExists(String taskId, String stageId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage
                WHERE id = ? AND task_id = ?
                """, Integer.class, stageId, taskId);
        return count != null && count == 1;
    }

    private WorkModel frozenTaskEngine(String trunkId, String taskId)
    {
        List<WorkModel> rows = jdbc.query("""
                SELECT context.work_model_snapshot
                FROM task_creation_context context
                JOIN tasks task ON task.id = context.task_id
                WHERE task.id = ? AND task.thread_id = ?
                  AND task.workflow_version = 'V2'
                """, (rs, row) -> WorkModelJson.deserialise(
                mapper, rs.getString("work_model_snapshot")), taskId, trunkId);
        if (rows.size() != 1 || rows.getFirst() == null) {
            throw new IllegalArgumentException("no typed Task engine: " + taskId);
        }
        return rows.getFirst();
    }

    private static WorkModel withEffort(WorkModel engine, String effort)
    {
        return new WorkModel(
                engine.kind(), engine.agentOrProvider(), engine.model(),
                engine.account(), normalize(effort));
    }

    private static String first(String... values)
    {
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String normalize(String effort)
    {
        if (effort == null || effort.isBlank()) {
            return null;
        }
        String normalized = effort.strip().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported reasoning effort: " + effort);
        }
        return normalized;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private record ScopeEfforts(String stage, String task, String trunk) {}
}
