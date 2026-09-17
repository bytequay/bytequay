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

import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCommitsSettingsService;
import com.bytequay.app.workspace.WorkspaceDeletionService;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestWorkspaceIssueDevelopmentController
{
    private final WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
    private final IssueDevelopmentService development = mock(IssueDevelopmentService.class);
    private final MockMvc mvc = standaloneSetup(new WorkspaceController(
            mock(WorkspaceRepositoryService.class), mock(WorkspaceDeletionService.class), mock(PullRequestService.class), mock(PullRequestRefreshService.class),
            mock(WorkspaceMetadataService.class), issues, mock(WorkspaceIssueRefreshService.class), mock(WorkspaceRemoteSourceService.class),
            mock(ModelSettingsService.class), mock(CredentialService.class), mock(WorkspaceCommitsSettingsService.class), development)).build();

    @Test
    void issueProjectionStaysFlatAndRetainsExistingTaskWorkspace()
            throws Exception
    {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        RepositoryIssue issue = new RepositoryIssue(70, 7, "Fix selection", "Description", "alice", "", "https://github.com/owner/repo/issues/7",
                ImmutableList.of("bug"), ImmutableList.of("alice"), 0, now, now);
        when(issues.find("workspace", 7)).thenReturn(Optional.of(issue));
        when(development.metadata(70)).thenReturn(new IssueDevelopmentService.Metadata(false,
                Optional.of(new IssueDevelopmentService.TaskLink("task-id", "original-workspace"))));
        mvc.perform(get("/api/workspaces/workspace/issues/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(70))
                .andExpect(jsonPath("$.title").value("Fix selection"))
                .andExpect(jsonPath("$.isGoal").value(false))
                .andExpect(jsonPath("$.developmentTask.taskId").value("task-id"))
                .andExpect(jsonPath("$.developmentTask.workspaceId").value("original-workspace"))
                .andExpect(jsonPath("$.issue").doesNotExist());
    }

    @Test
    void goalUpdatesUseLocalSourceService()
            throws Exception
    {
        when(development.setGoal("workspace", 7, true)).thenReturn(new IssueDevelopmentService.Metadata(true, Optional.empty()));
        mvc.perform(patch("/api/workspaces/workspace/issues/7/goal").contentType(MediaType.APPLICATION_JSON).content("{\"isGoal\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isGoal").value(true));
        verify(development).setGoal("workspace", 7, true);
    }

    @Test
    void attentionUsesEligibleQueryRoute()
            throws Exception
    {
        when(development.attention("workspace")).thenReturn(new RepositoryIssuePage(1, RepoRef.of("owner", "repo"), ImmutableList.of(),
                0, 100, 0, false, Optional.empty()));
        mvc.perform(get("/api/workspaces/workspace/issues/attention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues").isEmpty());
        verify(development).attention("workspace");
    }
}
