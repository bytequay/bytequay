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
import com.bytequay.app.workspace.WorkspaceDetails;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestRefreshControllers
{
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void dashboardRefreshCompletesBeforeTheRequestReturns()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        PullRequestController controller = new PullRequestController(pullRequests, refresh);

        controller.triggerSync();

        verify(refresh).refreshDashboard();
    }

    @Test
    void selectedPullRequestDetailUsesItsStoredId()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        PullRequestController controller = new PullRequestController(pullRequests, refresh);

        controller.detail(12L);
        controller.refreshDetail(12L);

        verify(pullRequests).getPullRequestDetail(12L);
        verify(refresh).refreshDetail(12L);
    }

    @Test
    void workspaceRefreshCompletesBeforeTheRequestReturns()
    {
        RepoRef repository = RepoRef.of("upstream", "project");
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        when(workspaces.getWorkspace("workspace-id")).thenReturn(details(repository));
        WorkspaceController controller = new WorkspaceController(
                workspaces,
                pullRequests,
                refresh,
                mock(WorkspaceMetadataService.class),
                mock(WorkspaceIssueService.class),
                mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class),
                mock(ModelSettingsService.class),
                mock(CredentialService.class));

        controller.syncPullRequests("workspace-id");

        var order = inOrder(workspaces, refresh);
        order.verify(workspaces).getWorkspace("workspace-id");
        order.verify(refresh).refreshRepository(repository);
    }

    private static WorkspaceDetails details(RepoRef repository)
    {
        WorkspaceSummary summary = new WorkspaceSummary(
                "workspace-id",
                "Project",
                1,
                "owner",
                "project",
                2L,
                repository.owner(),
                repository.repo(),
                2,
                repository.owner(),
                repository.repo(),
                Topology.FORK,
                "main",
                "main",
                "/tmp/workspace-id",
                CodeState.READY,
                MetadataState.READY,
                NOW,
                NOW,
                null,
                null,
                0);
        return new WorkspaceDetails(
                summary,
                "https://github.com/owner/project.git",
                "https://github.com/upstream/project.git",
                NOW,
                NOW);
    }
}
