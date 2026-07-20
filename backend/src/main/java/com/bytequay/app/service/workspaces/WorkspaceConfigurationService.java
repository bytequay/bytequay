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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.WorkspaceOnboardingDto;
import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionControlService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Settings, onboarding and reversible workspace-level actions. */
@Service
public class WorkspaceConfigurationService
{
    private static final Set<String> AUDIENCES = Set.of(
            "plan", "dev", "review", "ci-fix");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceService workspaces;
    private final AgentRunService runs;
    private final SessionControlService controls;

    public WorkspaceConfigurationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            WorkspaceService workspaces,
            AgentRunService runs,
            SessionControlService controls)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.controls = requireNonNull(controls, "controls is null");
    }

    public WorkspaceSettingsDto settings(String workspaceId)
    {
        workspaces.require(workspaceId);
        List<String> rows = jdbc.queryForList("""
                SELECT settings_json
                FROM workspace_settings
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        if (rows.isEmpty()) {
            return saveSettings(workspaceId, WorkspaceSettingsDto.defaults());
        }
        try {
            return mapper.readValue(rows.getFirst(), WorkspaceSettingsDto.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "invalid settings for workspace " + workspaceId, e);
        }
    }

    @Transactional
    public WorkspaceSettingsDto saveSettings(
            String workspaceId,
            WorkspaceSettingsDto settings)
    {
        workspaces.require(workspaceId);
        WorkspaceSettingsDto validated = validate(settings);
        try {
            jdbc.update("""
                    INSERT INTO workspace_settings (
                        workspace_id, settings_json, updated_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(workspace_id) DO UPDATE SET
                        settings_json = excluded.settings_json,
                        updated_at_ms = excluded.updated_at_ms
                    """,
                    workspaceId,
                    mapper.writeValueAsString(validated),
                    Instant.now().toEpochMilli());
            return validated;
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode workspace settings", e);
        }
    }

    public WorkspaceOnboardingDto onboarding(String workspaceId)
    {
        workspaces.require(workspaceId);
        List<WorkspaceOnboardingDto> rows = jdbc.query("""
                SELECT *
                FROM workspace_onboarding
                WHERE workspace_id = ?
                """,
                (rs, ignored) -> new WorkspaceOnboardingDto(
                        rs.getString("workspace_id"),
                        rs.getBoolean("clone_complete"),
                        rs.getString("sync_state"),
                        rs.getInt("sync_current"),
                        rs.getInt("sync_total"),
                        rs.getBoolean("memory_seed_complete"),
                        rs.getBoolean("first_trunk_complete"),
                        rs.getBoolean("memory_imported"),
                        nullableLong(rs.getObject("dismissed_at_ms")),
                        rs.getLong("updated_at_ms")),
                workspaceId);
        if (!rows.isEmpty()) {
            WorkspaceOnboardingDto current = rows.getFirst();
            if (!current.firstTrunkComplete()) {
                Long count = jdbc.queryForObject("""
                        SELECT count(*) FROM threads
                        WHERE workspace_id = ?
                          AND parent_task_id IS NULL
                          AND kind <> 'BRAIN_AGENT'
                        """, Long.class, workspaceId);
                if (count != null && count > 0) {
                    jdbc.update("""
                            UPDATE workspace_onboarding
                            SET first_trunk_complete = 1, updated_at_ms = ?
                            WHERE workspace_id = ?
                            """, Instant.now().toEpochMilli(), workspaceId);
                    return onboarding(workspaceId);
                }
            }
            return current;
        }
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO workspace_onboarding (
                    workspace_id, clone_complete, sync_state, updated_at_ms)
                VALUES (?, 1, 'ready', ?)
                """, workspaceId, now);
        return onboarding(workspaceId);
    }

    @Transactional
    public WorkspaceOnboardingDto dismissOnboarding(String workspaceId)
    {
        workspaces.require(workspaceId);
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                UPDATE workspace_onboarding
                SET dismissed_at_ms = ?, updated_at_ms = ?
                WHERE workspace_id = ?
                """, now, now, workspaceId);
        return onboarding(workspaceId);
    }

    public int pauseAllSessions(String workspaceId)
    {
        workspaces.require(workspaceId);
        int paused = 0;
        for (AgentRun run : runs.findByWorkspace(workspaceId)) {
            if (run.isLive() && !AgentRun.STATUS_PAUSED.equals(run.status())) {
                controls.pause(run.id());
                paused++;
            }
        }
        return paused;
    }

    @Transactional
    public void detach(String workspaceId)
    {
        workspaces.require(workspaceId);
        pauseAllSessions(workspaceId);
        jdbc.update("""
                UPDATE workspaces SET detached_at_ms = ?, updated_at_ms = ?
                WHERE id = ?
                """,
                Instant.now().toEpochMilli(), Instant.now().toEpochMilli(),
                workspaceId);
    }

    @Transactional
    public void reconnect(String workspaceId)
    {
        workspaces.require(workspaceId);
        jdbc.update("""
                UPDATE workspaces SET detached_at_ms = NULL, updated_at_ms = ?
                WHERE id = ?
                """, Instant.now().toEpochMilli(), workspaceId);
    }

    public boolean detached(String workspaceId)
    {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM workspaces
                WHERE id = ? AND detached_at_ms IS NOT NULL
                """, Long.class, workspaceId);
        return count != null && count > 0;
    }

    private static WorkspaceSettingsDto validate(WorkspaceSettingsDto value)
    {
        requireNonNull(value, "settings is null");
        if (value.sessionCapUsd() < 0 || value.dailyCapUsd() < 0) {
            throw new IllegalArgumentException("budget caps cannot be negative");
        }
        if (value.syncSeconds() < 15 || value.distillMinutes() < 1
                || value.brainBudgetChars() < 1_000) {
            throw new IllegalArgumentException("workspace cadence or budget is too small");
        }
        if (value.kbAudiences() == null
                || !AUDIENCES.containsAll(value.kbAudiences())) {
            throw new IllegalArgumentException("invalid KB audience");
        }
        return new WorkspaceSettingsDto(
                value.sessionCapUsd(),
                value.dailyCapUsd(),
                value.pauseAtCap(),
                value.syncSeconds(),
                value.brainBudgetChars(),
                value.distillMinutes(),
                List.copyOf(value.kbAudiences()),
                value.providers() == null ? Map.of()
                        : Map.copyOf(value.providers()),
                value.notifyCi(),
                value.notifyCompletions(),
                value.qualityScanEnabled(),
                value.remoteIssueIntakeEnabled());
    }

    private static Long nullableLong(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }
}
