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

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestWorkspaceConfigurationService
{
    @TempDir
    private Path tempDir;

    @Test
    void workspaceTaskLimitIsBackwardCompatibleAndSurvivesRestart()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("settings.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE workspace_settings(
                    workspace_id TEXT PRIMARY KEY,
                    settings_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO workspace_settings VALUES (
                    'workspace-1',
                    '{"sessionCapUsd":100,"dailyCapUsd":500,"pauseAtCap":true,
                      "syncSeconds":60,"brainBudgetChars":8000,"distillMinutes":30,
                      "kbAudiences":["plan","dev","review","ci-fix"],
                      "providers":{},"notifyCi":true,"notifyCompletions":false}',
                    1)
                """);
        CapacityManager capacity = mock(CapacityManager.class);
        WorkspaceConfigurationService service = service(jdbc, capacity);

        assertThat(service.settings("workspace-1").maxRunningTasks()).isNull();

        service.saveSettings("workspace-1", settings(2));

        WorkspaceConfigurationService restarted = service(jdbc, capacity);
        assertThat(restarted.settings("workspace-1").maxRunningTasks()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT json_extract(settings_json, '$.maxRunningTasks')
                FROM workspace_settings WHERE workspace_id = 'workspace-1'
                """, Integer.class)).isEqualTo(2);
        verify(capacity).policyChanged();

        assertThatThrownBy(() -> service.saveSettings("workspace-1", settings(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max running tasks");
        assertThat(restarted.settings("workspace-1").maxRunningTasks()).isEqualTo(2);
    }

    private static WorkspaceConfigurationService service(
            JdbcTemplate jdbc,
            CapacityManager capacity)
    {
        return new WorkspaceConfigurationService(
                jdbc,
                new ObjectMapper(),
                mock(WorkspaceService.class),
                mock(AgentRunService.class),
                mock(SessionControlService.class),
                capacity);
    }

    private static WorkspaceSettingsDto settings(Integer maxRunningTasks)
    {
        WorkspaceSettingsDto defaults = WorkspaceSettingsDto.defaults();
        return new WorkspaceSettingsDto(
                defaults.sessionCapUsd(),
                defaults.dailyCapUsd(),
                defaults.pauseAtCap(),
                defaults.syncSeconds(),
                defaults.brainBudgetChars(),
                defaults.distillMinutes(),
                List.copyOf(defaults.kbAudiences()),
                Map.copyOf(defaults.providers()),
                defaults.notifyCi(),
                defaults.notifyCompletions(),
                defaults.qualityScanEnabled(),
                defaults.remoteIssueIntakeEnabled(),
                maxRunningTasks);
    }
}
