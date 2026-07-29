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
package com.bytequay.app.web;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.scheduler.WorkspaceIssueIntakeMonitor;
import com.bytequay.app.scheduler.WorkspaceQualityScanCoordinator;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.bytequay.app.service.workspaces.WorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceConfigurationController.class)
class TestWorkspaceConfigurationController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WorkspaceConfigurationService configuration;

    @MockitoBean
    private WorkspaceCreationService creations;

    @MockitoBean
    private WorkspaceQualityScanCoordinator qualityScans;

    @MockitoBean
    private WorkspaceIssueIntakeMonitor issueIntake;

    @Test
    void readsAndWritesWorkspaceTaskCapacity()
            throws Exception
    {
        when(configuration.settings("workspace-1")).thenReturn(settings(2));
        when(configuration.saveSettings("workspace-1", settings(3)))
                .thenReturn(settings(3));

        mvc.perform(get("/api/workspaces/workspace-1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRunningTasks").value(2));

        mvc.perform(put("/api/workspaces/workspace-1/settings")
                        .contentType("application/json")
                        .content("""
                                {"sessionCapUsd":100,"dailyCapUsd":500,
                                 "pauseAtCap":true,"syncSeconds":60,
                                 "brainBudgetChars":8000,"distillMinutes":30,
                                 "kbAudiences":["plan","dev","review","ci-fix"],
                                 "providers":{},"notifyCi":true,
                                 "notifyCompletions":false,
                                 "qualityScanEnabled":false,
                                 "remoteIssueIntakeEnabled":false,
                                 "maxRunningTasks":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRunningTasks").value(3));

        ArgumentCaptor<WorkspaceSettingsDto> saved =
                ArgumentCaptor.forClass(WorkspaceSettingsDto.class);
        verify(configuration).saveSettings(eq("workspace-1"), saved.capture());
        assertThat(saved.getValue().maxRunningTasks()).isEqualTo(3);
    }

    private static WorkspaceSettingsDto settings(Integer maxRunningTasks)
    {
        WorkspaceSettingsDto defaults = WorkspaceSettingsDto.defaults();
        return new WorkspaceSettingsDto(
                defaults.sessionCapUsd(), defaults.dailyCapUsd(),
                defaults.pauseAtCap(), defaults.syncSeconds(),
                defaults.brainBudgetChars(), defaults.distillMinutes(),
                List.copyOf(defaults.kbAudiences()), Map.copyOf(defaults.providers()),
                defaults.notifyCi(), defaults.notifyCompletions(),
                defaults.qualityScanEnabled(), defaults.remoteIssueIntakeEnabled(),
                maxRunningTasks);
    }
}
