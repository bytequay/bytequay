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

import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.pr.MyActivityService;
import com.bytequay.app.service.pr.PrAnalyticsService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.filters.PullRequestFilters;
import com.bytequay.app.service.threads.PrTaskLinkService;
import com.bytequay.app.service.threads.PublishService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PullRequestController.class)
class TestPullRequestController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PullRequestService pullRequestService;

    @MockitoBean
    private PrAnalyticsService prAnalyticsService;

    @MockitoBean
    private MyActivityService myActivityService;

    @MockitoBean
    private PullRequestFilters prFilters;

    @MockitoBean
    private PrTaskLinkService prTaskLink;

    @MockitoBean
    private PublishService publishService;

    @MockitoBean
    private PRPublishService prPublishService;

    @MockitoBean
    private V2TaskControlService v2TaskControls;

    @MockitoBean
    private V2UserRemoteActionRuntime v2UserRemoteActions;

    @Test
    void testListReturns200()
            throws Exception
    {
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of(
                new PullRequest(1L, "owner/repo", 42, "Fix bug", "alice",
                        "https://github.com/owner/repo/pull/42",
                        Instant.parse("2024-05-25T00:00:00Z"),
                        Instant.parse("2024-06-01T00:00:00Z"), AUTHORED, ImmutableList.of(), null, false, null, null, null, ImmutableList.of(),
                        null, 0, 0, 0, null,
                        "open", null, null, null, null, null, null,
                        null, null, "feature/fix-bug")));

        mvc.perform(get("/prs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(42))
                .andExpect(jsonPath("$[0].title").value("Fix bug"));
    }

    @Test
    void testDetailReturns200()
            throws Exception
    {
        // PAT is resolved internally by PullRequestService via PatResolver.
        when(pullRequestService.getPullRequestDetail(eq("owner/repo"), eq(7))).thenReturn(stubDetail());

        mvc.perform(get("/prs/detail")
                        .param("repo", "owner/repo")
                        .param("number", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(7));
    }

    @Test
    void testFilterEndpointDelegatesToPullRequestFilters()
            throws Exception
    {
        PullRequest urgent = new PullRequest(1L, "owner/repo", 7, "Urgent fix", "alice",
                "https://github.com/owner/repo/pull/7",
                Instant.parse("2024-05-25T00:00:00Z"),
                Instant.parse("2024-06-01T00:00:00Z"), AUTHORED, ImmutableList.of(), null, false,
                null, null, null, ImmutableList.of(), null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null, "feature/urgent");
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of(urgent));
        when(prFilters.apply(eq("urgent"), any(), any())).thenReturn(ImmutableList.of(urgent));

        mvc.perform(get("/prs/filter/urgent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(7));
    }

    @Test
    void testFilterEndpointReturns400ForUnknownName()
            throws Exception
    {
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of());
        when(prFilters.apply(eq("not-a-filter"), any(), any()))
                .thenThrow(new IllegalArgumentException("unknown PR filter"));

        mvc.perform(get("/prs/filter/not-a-filter"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDetailWithoutStoredPatReturns401()
            throws Exception
    {
        when(pullRequestService.getPullRequestDetail(any(), eq(7))).thenThrow(new ResponseStatusException(
                HttpStatusCode.valueOf(401), "GitHub PAT not configured"));

        mvc.perform(get("/prs/detail").param("repo", "owner/repo").param("number", "7"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void v2TitleMutationUsesDurableRemoteAction()
            throws Exception
    {
        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(v2Pr()));

        mvc.perform(patch("/prs/title")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .header("Idempotency-Key", "title-command")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"new title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("new title"));

        verify(v2UserRemoteActions).updateTitle(
                "title-command", "task-42", "local-pr-42", "new title");
        verify(pullRequestService, never())
                .updatePullRequestTitle(any(), anyInt(), any());
    }

    @Test
    void v2CommentUsesTypedRemoteActionAndRequiresCommandIdentity()
            throws Exception
    {
        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(v2Pr()));

        mvc.perform(post("/prs/comment")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .header("Idempotency-Key", "comment-command")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"ship it\",\"close\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("commented"));

        verify(prPublishService).postComment("comment-command", "local-pr-42", "ship it");
        verify(pullRequestService, never())
                .commentOnPullRequest(any(), anyInt(), anyLong(), any(), anyBoolean());

        mvc.perform(post("/prs/comment")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .header("Idempotency-Key", "close-command")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"closing\",\"close\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("closed"));
        verify(v2UserRemoteActions).commentAndClose(
                "close-command", "task-42", "local-pr-42", "closing");

        mvc.perform(post("/prs/comment")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"again\",\"close\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void v2ApprovalMergeDequeueAndAutoMergeUseTaskProtocols()
            throws Exception
    {
        PR pr = v2Pr();
        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(pr));

        mvc.perform(post("/prs/approve")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .header("Idempotency-Key", "approve-command"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/merge")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .param("strategy", "squash")
                        .header("Idempotency-Key", "merge-command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merged").value(false));
        mvc.perform(delete("/prs/merge-queue")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99")
                        .header("Idempotency-Key", "dequeue-command"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/auto-merge")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .param("id", "99"))
                .andExpect(status().isOk());

        verify(prPublishService).publishReview(
                "approve-command", "local-pr-42", "APPROVE", List.of(), List.of(), "");
        verify(prPublishService).merge("merge-command", "local-pr-42", "squash");
        verify(prPublishService).dequeue("dequeue-command", "local-pr-42");
        verify(v2TaskControls).setAutoMerge("task-42", true);
        verify(pullRequestService, never()).approvePullRequest(any(), anyInt(), anyLong());
        verify(pullRequestService, never()).mergePullRequest(any(), anyInt(), anyLong(), any());
        verify(pullRequestService, never()).dequeuePullRequest(any(), anyInt(), anyLong());
        verify(pullRequestService, never()).enableAutoMerge(any(), anyInt(), anyLong(), any());
    }

    @Test
    void visibleV2PrControlsUseTheDurableExactOwner()
            throws Exception
    {
        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(v2Pr()));

        mvc.perform(post("/prs/rerun-checks")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "rerun"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/trigger-ci")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "trigger"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/draft")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "draft")
                        .contentType(APPLICATION_JSON)
                        .content("{\"draft\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/body")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "body")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"description\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/review-threads/101/reply")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "reply")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"fixed\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/issue-comments/102/body")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "edit-issue")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"edited\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/review-comments/103/body")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "edit-review")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"edited\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/prs/review-comments/104")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "delete-review"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/reviewers")
                        .param("repo", "owner/repo").param("number", "42")
                        .param("reviewer", "alice")
                        .header("Idempotency-Key", "add-reviewer"))
                .andExpect(status().isOk());
        mvc.perform(delete("/prs/reviewers")
                        .param("repo", "owner/repo").param("number", "42")
                        .param("reviewer", "alice")
                        .header("Idempotency-Key", "remove-reviewer"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/assignees")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "assignee")
                        .contentType(APPLICATION_JSON)
                        .content("{\"value\":\"bob\",\"selected\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/labels")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "label")
                        .contentType(APPLICATION_JSON)
                        .content("{\"value\":\"bug\",\"selected\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/review-comments")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "inline")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"nit\",\"path\":\"A.java\","
                                + "\"line\":7,\"side\":\"RIGHT\","
                                + "\"commitId\":\"ignored\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/42/reactions")
                        .param("repo", "owner/repo")
                        .header("Idempotency-Key", "react-pr")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"heart\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/review-comments/105/reactions")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "react-review")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"+1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/issue-comments/106/reactions")
                        .param("repo", "owner/repo").param("number", "42")
                        .header("Idempotency-Key", "react-issue")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"rocket\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/prs/review-threads/107/resolved")
                        .param("repo", "owner/repo").param("number", "42")
                        .param("prId", "99")
                        .header("Idempotency-Key", "resolve")
                        .contentType(APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isOk());

        verify(v2UserRemoteActions).rerunFailedChecks(
                "rerun", "task-42", "local-pr-42");
        verify(v2UserRemoteActions).triggerCiViaEmptyCommit(
                "trigger", "task-42", "local-pr-42");
        verify(v2UserRemoteActions).setDraft(
                "draft", "task-42", "local-pr-42", true);
        verify(v2UserRemoteActions).updateBody(
                "body", "task-42", "local-pr-42", "description");
        verify(v2UserRemoteActions).replyToReviewThread(
                "reply", "task-42", "local-pr-42", 101L, "fixed");
        verify(v2UserRemoteActions).editIssueComment(
                "edit-issue", "task-42", "local-pr-42", 102L, "edited");
        verify(v2UserRemoteActions).editReviewComment(
                "edit-review", "task-42", "local-pr-42", 103L, "edited");
        verify(v2UserRemoteActions).deleteReviewComment(
                "delete-review", "task-42", "local-pr-42", 104L);
        verify(v2UserRemoteActions).addReviewer(
                "add-reviewer", "task-42", "local-pr-42", "alice");
        verify(v2UserRemoteActions).removeReviewer(
                "remove-reviewer", "task-42", "local-pr-42", "alice");
        verify(v2UserRemoteActions).setAssignee(
                "assignee", "task-42", "local-pr-42", "bob", true);
        verify(v2UserRemoteActions).setLabel(
                "label", "task-42", "local-pr-42", "bug", false);
        verify(v2UserRemoteActions).createInlineComment(
                "inline", "task-42", "local-pr-42", "nit", "A.java", 7,
                "RIGHT", null, null);
        verify(v2UserRemoteActions).addPullRequestReaction(
                "react-pr", "task-42", "local-pr-42", "heart");
        verify(v2UserRemoteActions).addReviewCommentReaction(
                "react-review", "task-42", "local-pr-42", 105L, "+1");
        verify(v2UserRemoteActions).addIssueCommentReaction(
                "react-issue", "task-42", "local-pr-42", 106L, "rocket");
        verify(v2UserRemoteActions).setThreadResolved(
                "resolve", "task-42", "local-pr-42", 107L, true);
    }

    @Test
    void tasklessPrUsesDurableReviewTrunkMutation()
            throws Exception
    {
        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.empty());
        when(prPublishService.findExternalPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(externalPr()));

        mvc.perform(patch("/prs/title")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .header("Idempotency-Key", "external-title")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"new title\"}"))
                .andExpect(status().isOk());

        verify(v2UserRemoteActions).authorizeExternal(
                "external-title", "external-pr-42", SemanticAction.UPDATE_TITLE,
                ActionPayload.value("new title"));
        verify(pullRequestService, never())
                .updatePullRequestTitle(any(), anyInt(), any());
    }

    @Test
    void idOnlyMutationRequiresNumberAndUsesExactV2Owner()
            throws Exception
    {
        mvc.perform(delete("/prs/issue-comments/123")
                        .param("repo", "owner/repo"))
                .andExpect(status().isBadRequest());

        when(prPublishService.findV2TaskPullRequest("owner/repo", 42))
                .thenReturn(Optional.of(v2Pr()));
        mvc.perform(delete("/prs/issue-comments/123")
                        .param("repo", "owner/repo")
                        .param("number", "42")
                        .header("Idempotency-Key", "delete-command"))
                .andExpect(status().isOk());

        verify(v2UserRemoteActions).deleteIssueComment(
                "delete-command", "task-42", "local-pr-42", 123L);
        verify(pullRequestService, never()).deleteIssueComment(any(), anyLong());
    }

    private static PullRequestDetail stubDetail()
    {
        return new PullRequestDetail("owner/repo", 7, null, ImmutableList.of(), false,
                null, null, 10, 2, 3, 1, 1, 0, 0, ImmutableList.of(),
                PullRequestDetail.CiStatus.PASSING, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static PR v2Pr()
    {
        return PR.create(
                        "local-pr-42", "task-42", "feature/task-42", "main",
                        "Task PR", "", Instant.parse("2026-07-01T00:00:00Z"))
                .withRemote(
                        "owner/repo", 42, "https://github.com/owner/repo/pull/42",
                        Instant.parse("2026-07-01T00:01:00Z"))
                .withStatus(PR.STATUS_REMOTE_OPEN, Instant.parse("2026-07-01T00:02:00Z"));
    }

    private static PR externalPr()
    {
        return PR.createExternal(
                "external-pr-42", "owner/repo", 42,
                "https://github.com/owner/repo/pull/42", "@octocat",
                "feature/external", "main", "External PR", "",
                PR.STATUS_REMOTE_OPEN, Instant.parse("2026-07-01T00:00:00Z"),
                null, null);
    }
}
