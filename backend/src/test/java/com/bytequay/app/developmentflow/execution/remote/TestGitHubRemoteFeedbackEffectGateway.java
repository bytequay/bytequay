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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteFeedbackEffectGateway
{
    private static final Instant AUTHORIZED = Instant.parse("2026-07-28T00:00:00Z");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    private PullRequestRepository pullRequests;
    private PatResolver pats;
    private ExecutionContext execution;
    private GitHubRemoteFeedbackEffectGateway gateway;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        pats = mock(PatResolver.class);
        execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        gateway = new GitHubRemoteFeedbackEffectGateway(
                pullRequests, pats, mock(GitRunner.class),
                mock(GitRunnerProvisioningGit.class),
                mock(WorktreeWriterLeaseManager.class));
    }

    @Test
    void recoversOneExactInlineReplyWithoutPostingAgain()
            throws Exception
    {
        exactSubject("head-1");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("bot"));
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), eq(AUTHORIZED))).thenReturn(List.of(
                        comment(77L, 41L, "bot", "addressed",
                                AUTHORIZED.plusSeconds(1))));

        RemoteFeedbackEffectOperationHandler.EffectResult result = gateway.execute(
                effect(
                        RemoteFeedbackEffectOperationHandler.EffectKind.POST_INLINE_REPLY,
                        null, "thread-41", "41", null, "addressed"),
                execution);

        assertThat(result.proven()).isTrue();
        assertThat(result.externalEffectId()).isEqualTo("review-comment:77");
        verify(pullRequests, never()).replyToReviewComment(
                any(), any(), anyLong(), any());
    }

    @Test
    void refusesToPostAfterTheAuthorizedHeadMoves()
    {
        exactSubject("head-2");

        assertThatThrownBy(() -> gateway.execute(
                effect(
                        RemoteFeedbackEffectOperationHandler.EffectKind.POST_INLINE_REPLY,
                        null, "thread-41", "41", null, "addressed"),
                execution))
                .isInstanceOf(
                        RemoteFeedbackEffectOperationHandler.RetryableEffectException.class)
                .hasMessageContaining("outside the exact feedback authorization");
        verify(pullRequests, never()).replyToReviewComment(
                any(), any(), anyLong(), any());
    }

    @Test
    void submitsReviewAgainstTheExactAuthorizedCommit()
            throws Exception
    {
        exactSubject("head-1");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("bot"));
        when(pullRequests.listReviews("pat", PULL_REQUEST)).thenReturn(List.of());
        when(pullRequests.createReview(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(
                        new PullRequestReview(
                                91L, "bot", "looks good", "APPROVED", "head-1",
                                AUTHORIZED.plusSeconds(1), "url"));

        RemoteFeedbackEffectOperationHandler.EffectResult result = gateway.execute(
                effect(
                        RemoteFeedbackEffectOperationHandler.EffectKind.SUBMIT_REVIEW,
                        null, null, null, "APPROVE", "looks good"),
                execution);

        assertThat(result.externalEffectId()).isEqualTo("review:91");
        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(
                CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("pat"), eq(PULL_REQUEST), command.capture());
        assertThat(command.getValue().commitId()).contains("head-1");
        assertThat(command.getValue().event()).isEqualTo("APPROVE");
        assertThat(command.getValue().body()).contains("looks good");
    }

    private void exactSubject(String head)
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(
                new PrRawDetail(
                        "body", List.of(), false, true, "clean", 1, 1, 1,
                        0, List.of(), head, "feature", "acme/widget", "main",
                        "acme/widget", "open", false, "base-1", null));
    }

    private static RemoteFeedbackEffectOperationHandler.Effect effect(
            RemoteFeedbackEffectOperationHandler.EffectKind kind,
            String externalTarget,
            String threadId,
            String commentId,
            String reviewAction,
            String payload)
    {
        return new RemoteFeedbackEffectOperationHandler.Effect(
                "effect-1", "operation-1", "authorization-1", "batch-1", 1,
                kind, kind == RemoteFeedbackEffectOperationHandler.EffectKind.SUBMIT_REVIEW
                        ? null : "inbox-1",
                externalTarget, threadId, commentId, reviewAction, payload,
                "digest", "idempotency-1",
                RemoteFeedbackEffectOperationHandler.EffectStatus.REQUESTED,
                0, 3, "task-1", 1, "stage-1", 1, "head-1", "base-1",
                "acme/widget", "acme/widget", 17, "/tmp/worktree", "feature",
                AUTHORIZED, null, null);
    }

    private static PrReviewThreadMessage comment(
            long id,
            Long root,
            String author,
            String body,
            Instant createdAt)
    {
        return new PrReviewThreadMessage(
                id, root, null, author, body, "src/App.java", 10, "RIGHT", "@@",
                "head-1", createdAt, null, false, null, null, 10, null,
                "MEMBER", "thread-41", false, null);
    }

    private static UserProfile user(String login)
    {
        return new UserProfile(
                login, login, "avatar", "html", 0, 0, 0,
                null, null, null, null, false);
    }
}
