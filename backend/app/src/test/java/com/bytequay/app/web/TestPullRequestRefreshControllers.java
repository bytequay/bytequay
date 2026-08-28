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
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.Topology;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestPullRequestRefreshControllers
{
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void dashboardRefreshCompletesBeforeTheRequestReturns()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        PullRequestController controller = new PullRequestController(pullRequests, refresh);

        controller.triggerSync(false);

        verify(refresh).refreshDashboard();
    }

    @Test
    void dashboardListRefreshUsesTheExistingNarrowSync()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        PullRequestController controller = new PullRequestController(pullRequests, refresh);

        controller.triggerSync(true);

        verify(refresh).refreshDashboardRows();
    }

    @Test
    void dashboardListRefreshReportsAnInFlightRefresh()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        PullRequestController controller = new PullRequestController(pullRequests, refresh);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "refresh already in progress"))
                .when(refresh).refreshDashboardRows();

        standaloneSetup(controller).build()
                .perform(post("/api/settings/sync/trigger").queryParam("listOnly", "true"))
                .andExpect(status().isConflict());
    }

    @Test
    void servesDetailForARowIdSoTheAttentionListCanOpenIt()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));
        when(pullRequests.getPullRequestDetail(50L)).thenReturn(detail());

        assertThat(controller.detail(50L)).isEqualTo(detail());

        verify(pullRequests).getPullRequestDetail(50L);
    }

    @Test
    void forwardsPullRequestReactionToggleRequest()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));

        standaloneSetup(controller).build().perform(post("/prs/reactions")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"issue_comment","commentId":41,"content":"heart"}
                                """))
                .andExpect(status().isNoContent());

        verify(pullRequests).toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart");
    }

    @Test
    void returnsTheDetailProducedByCommentWrites()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));
        PullRequestDetail detail = detail();
        InlineReviewComment inline = new InlineReviewComment(
                "src/Main.java", "Please simplify this", 18, "RIGHT", null, null, "head-sha");
        when(pullRequests.commentOnPullRequest("owner/repo", 7, "hello")).thenReturn(detail);
        when(pullRequests.createReviewComment("owner/repo", 7, inline)).thenReturn(detail);

        assertThat(controller.comment(
                "owner/repo", 7, new PullRequestController.CommentRequest("hello"))).isSameAs(detail);
        assertThat(controller.reviewComment("owner/repo", 7, inline)).isSameAs(detail);
    }

    @Test
    void reportsAnUncertainReviewCommentTimeout()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));
        doThrow(new ResourceAccessException("Operation timed out"))
                .when(pullRequests).createReviewComment(any(), anyInt(), any());

        standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(post("/prs/review-comment")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "path":"src/Main.java",
                                  "body":"Please simplify this",
                                  "line":18,
                                  "side":"RIGHT",
                                  "commitId":"head-sha"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "GitHub did not respond. Refresh before retrying; a submitted action may already have completed."));
    }

    @Test
    void forwardsIssueCloseRequest()
            throws Exception
    {
        WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
        WorkspaceController controller = new WorkspaceController(
                mock(WorkspaceRepositoryService.class),
                mock(PullRequestService.class),
                mock(PullRequestRefreshService.class),
                mock(WorkspaceMetadataService.class),
                issues,
                mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class),
                mock(ModelSettingsService.class),
                mock(CredentialService.class));

        standaloneSetup(controller).build().perform(post("/api/workspaces/workspace-id/issues/9/close"))
                .andExpect(status().isNoContent());

        verify(issues).close("workspace-id", 9);
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
                NOW,
                NOW,
                0);
        return new WorkspaceDetails(
                summary,
                "https://github.com/owner/project.git",
                "https://github.com/upstream/project.git",
                NOW,
                NOW);
    }

    private static PullRequestDetail detail()
    {
        return new PullRequestDetail(
                12L,
                "owner/repo",
                7,
                "Title",
                "https://github.test/owner/repo/pull/7",
                "open",
                false,
                true,
                "clean",
                "me",
                "https://avatars.test/me",
                NOW,
                NOW,
                null,
                "main",
                "feature",
                "body",
                ImmutableList.of(),
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                1,
                1,
                1,
                0,
                PullRequestDetail.CiStatus.NONE,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }
}
