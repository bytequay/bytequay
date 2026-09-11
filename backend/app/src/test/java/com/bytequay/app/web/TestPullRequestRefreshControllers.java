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
import com.bytequay.app.workspace.WorkspaceDeletionService;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.Workspace.Topology;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestPullRequestRefreshControllers
{
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void attentionKeepsTheFlatPullRequestShapeAndIncludesCachedActivity()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        String avatar = "https://avatars.githubusercontent.com/u/123?v=4";
        List<PullRequest> candidates = ImmutableList.of(attentionPullRequest(12), attentionPullRequest(13));
        when(pullRequests.listAttentionCandidates()).thenReturn(candidates);
        when(pullRequests.findCachedAuthorAvatarUrls(ImmutableSet.of(12L, 13L))).thenReturn(ImmutableMap.of(12L, avatar));
        when(pullRequests.findCachedLatestActivities(candidates)).thenReturn(ImmutableMap.of(12L, new PrActivitySummary(
                "reviewed", Optional.of("reviewer"), NOW, Optional.of("APPROVED"), Optional.empty(), Optional.empty())));

        standaloneSetup(new PullRequestController(pullRequests, refresh)).build()
                .perform(get("/prs/attention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].repo").value("trinodb/trino"))
                .andExpect(jsonPath("$[0].author").value("alice"))
                .andExpect(jsonPath("$[0].authorAvatarUrl").value(avatar))
                .andExpect(jsonPath("$[0].latestActivity.event").value("reviewed"))
                .andExpect(jsonPath("$[0].latestActivity.actor").value("reviewer"))
                .andExpect(jsonPath("$[0].latestActivity.state").value("APPROVED"))
                .andExpect(jsonPath("$[0].latestActivity.body").doesNotExist())
                .andExpect(jsonPath("$[0].pullRequest").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(13))
                .andExpect(jsonPath("$[1].authorAvatarUrl").value(nullValue()))
                .andExpect(jsonPath("$[1].latestActivity").value(nullValue()));

        verify(pullRequests).listAttentionCandidates();
        verify(pullRequests).findCachedAuthorAvatarUrls(ImmutableSet.of(12L, 13L));
        verify(pullRequests).findCachedLatestActivities(candidates);
        verifyNoMoreInteractions(pullRequests);
        verifyNoInteractions(refresh);
    }

    private static PullRequest attentionPullRequest(long id)
    {
        return new PullRequest(
                id, "trinodb/trino", (int) id, "Review this", "alice", "https://github.com/trinodb/trino/pull/" + id,
                NOW, NOW, PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false,
                null, null, null, ImmutableList.of("viewer"), null, 0, 0, 0, null, "open", null, null,
                null, null, null, "feature", null, null, null, null);
    }

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
    void forwardsPullRequestReactionToggleRequestWithoutKnownViewerState()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));
        when(pullRequests.toggleReaction(
                "owner/repo", 7, "issue_comment", 41L, "heart", null)).thenReturn(true);

        standaloneSetup(controller).build().perform(post("/prs/reactions")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"issue_comment","commentId":41,"content":"heart"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerHasReacted").value(true));

        verify(pullRequests).toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", null);
    }

    @Test
    void forwardsDesiredPullRequestReactionState()
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
                                {"target":"pull_request","content":"heart","viewerHasReacted":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerHasReacted").value(false));

        verify(pullRequests).toggleReaction("owner/repo", 7, "pull_request", null, "heart", false);
    }

    @Test
    void forwardsPullRequestDescriptionUpdate()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(
                pullRequests, mock(PullRequestRefreshService.class));

        standaloneSetup(controller).build().perform(patch("/prs/description")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"Updated description"}
                                """))
                .andExpect(status().isNoContent());

        verify(pullRequests).updateDescription("owner/repo", 7, "Updated description");
    }

    @Test
    void forwardsCommentEditsAndRejectsAMissingCommentIdBeforeTheService()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestController controller = new PullRequestController(pullRequests, mock(PullRequestRefreshService.class));
        when(pullRequests.editComment("owner/repo", 7, "review_comment", 41L, "Edited")).thenReturn(detail());
        var mvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(patch("/prs/comments")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"review_comment","commentId":41,"body":"Edited"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(7));
        mvc.perform(patch("/prs/comments")
                        .queryParam("repo", "owner/repo")
                        .queryParam("number", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"review_comment","body":"Edited"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("commentId is required"));

        verify(pullRequests).editComment("owner/repo", 7, "review_comment", 41L, "Edited");
        verifyNoMoreInteractions(pullRequests);
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
                mock(WorkspaceDeletionService.class),
                mock(PullRequestService.class),
                mock(PullRequestRefreshService.class),
                mock(WorkspaceMetadataService.class),
                issues,
                mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class),
                mock(ModelSettingsService.class),
                mock(CredentialService.class),
                mock(WorkspaceCommitsSettingsService.class));

        standaloneSetup(controller).build().perform(post("/api/workspaces/workspace-id/issues/9/close"))
                .andExpect(status().isNoContent());

        verify(issues).close("workspace-id", 9);
    }

    @Test
    void unwatchAndDeleteUseSeparateEndpoints()
            throws Exception
    {
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        WorkspaceDeletionService deletion = mock(WorkspaceDeletionService.class);
        WorkspaceController controller = new WorkspaceController(
                workspaces, deletion, mock(PullRequestService.class), mock(PullRequestRefreshService.class),
                mock(WorkspaceMetadataService.class), mock(WorkspaceIssueService.class), mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class), mock(ModelSettingsService.class), mock(CredentialService.class), mock(WorkspaceCommitsSettingsService.class));
        var mvc = standaloneSetup(controller).build();

        mvc.perform(delete("/api/workspaces/workspace-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("removed"));
        verify(workspaces).unwatch("workspace-id");
        verifyNoInteractions(deletion);

        mvc.perform(delete("/api/workspaces/workspace-id/data").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmed":true,"repositoryName":"owner/repo"}
                                """))
                .andExpect(status().isOk());
        verify(deletion).delete("workspace-id", true, "owner/repo");
    }

    @Test
    void retiredEndpointReturnsRetainedIdentitiesSeparatelyFromWatchedWorkspaces()
            throws Exception
    {
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        when(workspaces.listRetiredWorkspaces()).thenReturn(ImmutableList.of(workspaceSummary("retired-id", 100)));
        when(workspaces.listWorkspaces()).thenReturn(ImmutableList.of(workspaceSummary("watched-id", 200)));
        WorkspaceDeletionService deletion = mock(WorkspaceDeletionService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        CredentialService credentials = mock(CredentialService.class);
        WorkspaceController controller = new WorkspaceController(
                workspaces, deletion, mock(PullRequestService.class), refresh,
                mock(WorkspaceMetadataService.class), mock(WorkspaceIssueService.class), mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class), mock(ModelSettingsService.class), credentials, mock(WorkspaceCommitsSettingsService.class));
        var mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/workspaces/retired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("retired-id"))
                .andExpect(jsonPath("$[0].selectedRepositoryId").value(100))
                .andExpect(jsonPath("$[0].localRepositoryPath").value("/tmp/bytequay-workspaces/retired-id/repository"));
        mvc.perform(get("/api/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("watched-id"));

        verify(workspaces).listRetiredWorkspaces();
        verify(workspaces).listWorkspaces();
        verifyNoMoreInteractions(workspaces);
        verifyNoInteractions(deletion, refresh, credentials);
    }

    @Test
    void workspaceRefreshCompletesBeforeTheRequestReturns()
    {
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        WorkspaceController controller = new WorkspaceController(
                workspaces,
                mock(WorkspaceDeletionService.class),
                pullRequests,
                refresh,
                mock(WorkspaceMetadataService.class),
                mock(WorkspaceIssueService.class),
                mock(WorkspaceIssueRefreshService.class),
                mock(WorkspaceRemoteSourceService.class),
                mock(ModelSettingsService.class),
                mock(CredentialService.class),
                mock(WorkspaceCommitsSettingsService.class));

        controller.syncPullRequests("workspace-id");

        verify(refresh).refreshWorkspace("workspace-id");
        verifyNoInteractions(workspaces);
    }

    private static WorkspaceSummary workspaceSummary(String id, long repositoryId)
    {
        return new WorkspaceSummary(id, "owner/repo", repositoryId, "owner", "repo", null, null, null,
                repositoryId, "owner", "repo", Topology.DIRECT, "main", null,
                "/tmp/bytequay-workspaces/" + id + "/repository", NOW, NOW, 0);
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
