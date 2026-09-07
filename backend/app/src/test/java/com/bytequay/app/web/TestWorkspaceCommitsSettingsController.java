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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCommitsSettingsService;
import com.bytequay.app.workspace.WorkspaceCommitsTarget;
import com.bytequay.app.workspace.WorkspaceDeletionService;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestWorkspaceCommitsSettingsController
{
    @Test
    void settingsExposeTargetAndExplicitSaveAndResetReturnResolvedValues()
            throws Exception
    {
        WorkspaceCommitsSettingsService settings = mock(WorkspaceCommitsSettingsService.class);
        WorkspaceCommitsTarget automatic = new WorkspaceCommitsTarget("upstream", "master", false);
        when(settings.get("workspace")).thenReturn(automatic);
        when(settings.reset("workspace")).thenReturn(automatic);
        when(settings.save("workspace", "release/next")).thenReturn(new WorkspaceCommitsTarget("upstream", "release/next", true));
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        WorkspaceDeletionService deletion = mock(WorkspaceDeletionService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        WorkspaceController controller = new WorkspaceController(workspaces, deletion, mock(PullRequestService.class), refresh,
                mock(WorkspaceMetadataService.class), mock(WorkspaceIssueService.class), mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class), mock(ModelSettingsService.class), mock(CredentialService.class), settings);
        var mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/workspaces/workspace/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitsTarget.remote").value("upstream"))
                .andExpect(jsonPath("$.commitsTarget.branch").value("master"))
                .andExpect(jsonPath("$.commitsTarget.overridden").value(false));
        mvc.perform(put("/api/workspaces/workspace/commits-target").contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"release/next\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remote").value("upstream"))
                .andExpect(jsonPath("$.branch").value("release/next"))
                .andExpect(jsonPath("$.overridden").value(true));
        mvc.perform(delete("/api/workspaces/workspace/commits-target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remote").value("upstream"))
                .andExpect(jsonPath("$.branch").value("master"))
                .andExpect(jsonPath("$.overridden").value(false));

        verify(settings).save("workspace", "release/next");
        verify(settings).reset("workspace");
        verifyNoInteractions(workspaces, deletion, refresh);
    }
}
