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

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.PermissionDeniedException;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.RemoteTruthPendingException;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.SubjectRejectedException;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitHub adapter that re-proves queue mode and head before every mutation. */
@Component
public final class GitHubMergeEffects
        implements MergeOperationHandler.MergeEffects
{
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;

    public GitHubMergeEffects(PullRequestRepository pullRequests, PatResolver pats)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    @Override
    public EffectEvidence execute(MergeRequest request, EffectClaim claim)
    {
        requireNonNull(request, "request is null");
        requireNonNull(claim, "claim is null");
        try {
            Subject subject = subject(request);
            PrRawDetail detail = exactDetail(subject, request);
            if (detail.merged()) {
                return new EffectEvidence(
                        detail.mergeCommitSha(),
                        "exact pull request was already merged; awaiting observer",
                        true);
            }
            return switch (claim.spec().kind()) {
                case DIRECT_MERGE -> executeDirect(subject, request);
                case ENTER_QUEUE -> executeQueue(subject, request);
            };
        }
        catch (ResponseStatusException failure) {
            throw permission(failure);
        }
    }

    @Override
    public EffectEvidence probe(MergeRequest request, EffectClaim claim)
    {
        requireNonNull(request, "request is null");
        requireNonNull(claim, "claim is null");
        try {
            Subject subject = subject(request);
            PrRawDetail detail = exactDetail(subject, request);
            if (detail.merged()) {
                return new EffectEvidence(
                        detail.mergeCommitSha(),
                        "GitHub probe sees merged; awaiting persisted RemoteObserver truth",
                        true);
            }
            if (claim.spec().kind() == EffectKind.ENTER_QUEUE) {
                PullRequestRepository.MergeQueueInfo queue =
                        pullRequests.fetchMergeQueueInfo(subject.pat(), subject.ref());
                requireQueueMode(request, queue.queueConfigured());
                return new EffectEvidence(
                        null,
                        queue.entryState() == null
                                ? "GitHub probe does not yet see a queue entry"
                                : "GitHub probe sees queue state " + queue.entryState()
                                        + "; awaiting persisted RemoteObserver truth",
                        queue.entryState() != null);
            }
            PullRequestRepository.MergeQueueInfo queue =
                    pullRequests.fetchMergeQueueInfo(subject.pat(), subject.ref());
            requireQueueMode(request, queue.queueConfigured());
            return new EffectEvidence(
                    null, "GitHub probe sees the exact head still open", false);
        }
        catch (ResponseStatusException failure) {
            throw permission(failure);
        }
    }

    private static RuntimeException permission(ResponseStatusException failure)
    {
        String reason = failure.getReason();
        boolean explicitDenial = reason != null
                && (reason.contains("cannot perform this action")
                    || reason.contains("Resource not accessible"));
        if (failure.getStatusCode() == HttpStatus.UNAUTHORIZED
                || (failure.getStatusCode() == HttpStatus.FORBIDDEN
                    && explicitDenial)) {
            return new PermissionDeniedException(
                    "GitHub identity cannot merge the exact pull request");
        }
        return failure;
    }

    private EffectEvidence executeDirect(Subject subject, MergeRequest request)
    {
        PullRequestRepository.MergeQueueInfo queue =
                pullRequests.fetchMergeQueueInfo(subject.pat(), subject.ref());
        requireQueueMode(request, queue.queueConfigured());
        MergeResult result = pullRequests.mergePullRequest(
                subject.pat(), subject.ref(),
                new MergePullRequestCommand(
                        "squash", Optional.empty(), Optional.empty(),
                        Optional.of(request.headSha())));
        if (!result.merged() || result.queued()) {
            throw new SubjectRejectedException(
                    "GitHub rejected direct merge for the exact head: "
                            + result.message());
        }
        return new EffectEvidence(
                result.sha(), "direct merge accepted; awaiting RemoteObserver", false);
    }

    private EffectEvidence executeQueue(Subject subject, MergeRequest request)
    {
        Optional<PullRequestRepository.MergeQueueProbe> queue =
                pullRequests.probeMergeQueue(subject.pat(), subject.ref());
        requireQueueMode(request, queue.isPresent());
        String nodeId = queue.orElseThrow().pullRequestNodeId();
        MergeResult result = pullRequests.enqueuePullRequest(
                subject.pat(), nodeId, request.headSha());
        if (!result.queued() || result.merged()) {
            throw new SubjectRejectedException(
                    "GitHub did not accept the exact pull request into merge queue");
        }
        return new EffectEvidence(
                null, "merge queue mutation accepted; awaiting RemoteObserver", false);
    }

    private PrRawDetail exactDetail(Subject subject, MergeRequest request)
    {
        PrRawDetail detail = pullRequests.fetchPrDetail(subject.pat(), subject.ref());
        if (!request.headSha().equals(detail.headSha())
                || !request.baseSha().equals(detail.baseSha())) {
            throw new RemoteTruthPendingException(
                    "remote pull request head/base moved; awaiting RemoteObserver");
        }
        boolean closed = "closed".equalsIgnoreCase(detail.state()) && !detail.merged();
        if (closed) {
            throw new RemoteTruthPendingException(
                    "remote pull request closed; awaiting RemoteObserver");
        }
        return detail;
    }

    private static void requireQueueMode(MergeRequest request, boolean queueConfigured)
    {
        boolean exact = request.mode() == MergeMode.MERGE_QUEUE
                ? queueConfigured : !queueConfigured;
        if (!exact) {
            throw new SubjectRejectedException(
                    "merge queue capability changed; refusing unsafe mode fallback");
        }
    }

    private Subject subject(MergeRequest request)
    {
        RepoRef repository = RepoRef.parse(request.remoteRepositoryId());
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(), request.remotePrNumber());
        return new Subject(ref, pats.resolve(repository.fullName()));
    }

    private record Subject(PullRequestRef ref, String pat)
    {
        private Subject
        {
            requireNonNull(ref, "ref is null");
            requireNonNull(pat, "pat is null");
        }
    }
}
