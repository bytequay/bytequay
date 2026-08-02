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
package com.bytequay.app.developmentflow.execution.merge;

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimSpec;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.PermissionDeniedException;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.PreparedEffect;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.RemoteTruthPendingException;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.SubjectRejectedException;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubMergeEffects
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    private PullRequestRepository pullRequests;
    private GitHubMergeEffects effects;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        when(pats.resolve("owner/repo")).thenReturn("pat");
        effects = new GitHubMergeEffects(pullRequests, pats);
    }

    @Test
    void directAuthorizationNeverFallsBackWhenQueueCapabilityChanges()
    {
        MergeRequest request = request(MergeMode.DIRECT);
        PrRawDetail detail = openExactDetail(request);
        when(pullRequests.fetchPrDetail(anyString(), any()))
                .thenReturn(detail);
        when(pullRequests.fetchMergeQueueInfo(anyString(), any()))
                .thenReturn(new PullRequestRepository.MergeQueueInfo(true, null));

        assertThatThrownBy(() -> execute(request, claim(EffectKind.DIRECT_MERGE)))
                .isInstanceOf(SubjectRejectedException.class)
                .hasMessageContaining("unsafe mode fallback");
        verify(pullRequests, never()).mergePullRequest(anyString(), any(), any());
    }

    @Test
    void queueAuthorizationNeverFallsBackToDirectMerge()
    {
        MergeRequest request = request(MergeMode.MERGE_QUEUE);
        PrRawDetail detail = openExactDetail(request);
        when(pullRequests.fetchPrDetail(anyString(), any()))
                .thenReturn(detail);
        when(pullRequests.fetchMergeQueueInfo(anyString(), any()))
                .thenReturn(new PullRequestRepository.MergeQueueInfo(false, null));

        assertThatThrownBy(() -> execute(request, claim(EffectKind.ENTER_QUEUE)))
                .isInstanceOf(SubjectRejectedException.class)
                .hasMessageContaining("unsafe mode fallback");
        verify(pullRequests, never()).mergePullRequest(anyString(), any(), any());
        verify(pullRequests, never()).enqueuePullRequest(
                anyString(), anyString(), anyString());
    }

    @Test
    void missingBaseProofRejectsBeforeAnyMergeMutation()
    {
        MergeRequest request = request(MergeMode.DIRECT);
        PrRawDetail detail = openExactDetail(request);
        when(detail.baseSha()).thenReturn(null);
        when(pullRequests.fetchPrDetail(anyString(), any())).thenReturn(detail);

        assertThatThrownBy(() -> execute(request, claim(EffectKind.DIRECT_MERGE)))
                .isInstanceOf(RemoteTruthPendingException.class)
                .hasMessageContaining("awaiting RemoteObserver");
        verify(pullRequests, never()).fetchMergeQueueInfo(anyString(), any());
        verify(pullRequests, never()).mergePullRequest(anyString(), any(), any());
    }

    @Test
    void queueMutationCarriesTheAuthorizedHeadAsACasFence()
    {
        MergeRequest request = request(MergeMode.MERGE_QUEUE);
        PrRawDetail detail = openExactDetail(request);
        when(pullRequests.fetchPrDetail(anyString(), any()))
                .thenReturn(detail);
        when(pullRequests.fetchMergeQueueInfo(anyString(), any()))
                .thenReturn(new PullRequestRepository.MergeQueueInfo(true, null));
        when(pullRequests.pullRequestNodeId(anyString(), any()))
                .thenReturn(Optional.of("node"));
        when(pullRequests.enqueuePullRequest("pat", "node", "head"))
                .thenReturn(MergeResult.enqueued("queued"));

        EffectClaim claim = claim(EffectKind.ENTER_QUEUE);
        PreparedEffect prepared = effects.prepare(request, claim.spec());

        verify(pullRequests, never()).enqueuePullRequest(
                anyString(), anyString(), anyString());
        effects.perform(request, claim, prepared);

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).fetchPrDetail(anyString(), any());
        order.verify(pullRequests).fetchMergeQueueInfo(anyString(), any());
        order.verify(pullRequests).pullRequestNodeId(anyString(), any());
        order.verify(pullRequests).enqueuePullRequest("pat", "node", "head");
        verify(pullRequests, never()).probeMergeQueue(anyString(), any());
        verify(pullRequests, never()).mergePullRequest(anyString(), any(), any());
        verify(pullRequests, never()).enqueuePullRequest("pat", "node");
    }

    @Test
    void directMutationUsesTheFrozenMergeMethodAndHeadFence()
    {
        MergeRequest request = request(MergeMode.DIRECT, "rebase");
        PrRawDetail detail = openExactDetail(request);
        when(pullRequests.fetchPrDetail(anyString(), any()))
                .thenReturn(detail);
        when(pullRequests.fetchMergeQueueInfo(anyString(), any()))
                .thenReturn(new PullRequestRepository.MergeQueueInfo(false, null));
        when(pullRequests.mergePullRequest(anyString(), any(), any()))
                .thenReturn(new MergeResult("merge-sha", true, "merged"));

        execute(request, claim(EffectKind.DIRECT_MERGE));

        verify(pullRequests).mergePullRequest(eq("pat"), any(), argThat(command ->
                command.mergeMethod().equals("rebase")
                        && command.sha().orElseThrow().equals("head")));
    }

    @Test
    void rateLimitIsNotMisclassifiedAsPermanentPermissionDenial()
    {
        MergeRequest request = request(MergeMode.DIRECT);
        when(pullRequests.fetchPrDetail(anyString(), any())).thenThrow(
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "API rate limit exceeded"));

        assertThatThrownBy(() -> execute(
                request, claim(EffectKind.DIRECT_MERGE)))
                .isInstanceOf(ResponseStatusException.class)
                .isNotInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void explicitTokenDenialIsPermanent()
    {
        MergeRequest request = request(MergeMode.DIRECT);
        when(pullRequests.fetchPrDetail(anyString(), any())).thenThrow(
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "The configured GitHub token cannot perform this action"));

        assertThatThrownBy(() -> execute(
                request, claim(EffectKind.DIRECT_MERGE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void unknownQueueCapabilityCannotFallThroughToDirectMerge()
    {
        MergeRequest request = request(MergeMode.DIRECT);
        PrRawDetail detail = openExactDetail(request);
        when(pullRequests.fetchPrDetail(anyString(), any()))
                .thenReturn(detail);
        when(pullRequests.fetchMergeQueueInfo(anyString(), any())).thenThrow(
                new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "incomplete GraphQL data"));

        assertThatThrownBy(() -> execute(
                request, claim(EffectKind.DIRECT_MERGE)))
                .isInstanceOf(ResponseStatusException.class);
        verify(pullRequests, never()).mergePullRequest(anyString(), any(), any());
    }

    private void execute(MergeRequest request, EffectClaim claim)
    {
        PreparedEffect prepared = effects.prepare(request, claim.spec());
        effects.perform(request, claim, prepared);
    }

    private static MergeRequest request(MergeMode mode)
    {
        return request(mode, "squash");
    }

    private static MergeRequest request(MergeMode mode, String mergeMethod)
    {
        return new MergeRequest(
                "merge-row", "authorization", "readiness", "operation",
                "remote-stage", "task", "trunk", "workspace", 1, 1, 1,
                mode, mergeMethod, OperationStatus.REQUESTED, 0, 4, 0,
                mode == MergeMode.MERGE_QUEUE ? 2 : 0,
                "head", "base", "owner/repo", 17, "readiness", "V2",
                "ACTIVE", 1, "remote-stage", 1, "MERGING", null);
    }

    private static EffectClaim claim(EffectKind kind)
    {
        Integer effectOrdinal = kind == EffectKind.ENTER_QUEUE ? 1 : null;
        String key = kind == EffectKind.ENTER_QUEUE
                ? "operation:queue:1" : "operation:direct";
        return new EffectClaim(
                "effect", "merge-row", 1,
                new ClaimSpec(ClaimMode.EXECUTE, kind, effectOrdinal, "readiness", key),
                "worker", NOW, NOW.plusSeconds(30));
    }

    private static PrRawDetail openExactDetail(MergeRequest request)
    {
        PrRawDetail detail = mock(PrRawDetail.class);
        when(detail.headSha()).thenReturn(request.headSha());
        when(detail.baseSha()).thenReturn(request.baseSha());
        when(detail.state()).thenReturn("open");
        when(detail.merged()).thenReturn(false);
        return detail;
    }
}
