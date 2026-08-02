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
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.PermissionDeniedException;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.PreparedEffect;
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
    public PreparedEffect prepare(MergeRequest request, ClaimSpec claim)
    {
        requireNonNull(request, "request is null");
        requireNonNull(claim, "claim is null");
        try {
            requireEffectKind(request, claim.kind());
            Subject subject = subject(request);
            PrRawDetail detail = exactDetail(subject, request);
            if (detail.merged()) {
                return new PreparedGitHubEffect(
                        request, claim, subject, null,
                        new EffectEvidence(
                                detail.mergeCommitSha(),
                                "exact pull request was already merged; awaiting observer",
                                true));
            }
            PullRequestRepository.MergeQueueInfo queue =
                    pullRequests.fetchMergeQueueInfo(subject.pat(), subject.ref());
            requireQueueMode(request, queue.queueConfigured());
            if (claim.mode() == ClaimMode.PROBE) {
                EffectEvidence evidence = claim.kind() == EffectKind.ENTER_QUEUE
                        ? new EffectEvidence(
                                null,
                                queue.entryState() == null
                                        ? "GitHub probe does not yet see a queue entry"
                                        : "GitHub probe sees queue state " + queue.entryState()
                                                + "; awaiting persisted RemoteObserver truth",
                                queue.entryState() != null)
                        : new EffectEvidence(
                                null, "GitHub probe sees the exact head still open", false);
                return new PreparedGitHubEffect(
                        request, claim, subject, null, evidence);
            }
            String queueNodeId = null;
            if (claim.kind() == EffectKind.ENTER_QUEUE) {
                queueNodeId = pullRequests.pullRequestNodeId(
                                subject.pat(), subject.ref())
                        .filter(id -> !id.isBlank())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "GitHub did not return the pull request node id"));
            }
            return new PreparedGitHubEffect(
                    request, claim, subject, queueNodeId, null);
        }
        catch (ResponseStatusException failure) {
            throw permission(failure);
        }
    }

    @Override
    public EffectEvidence perform(
            MergeRequest request, EffectClaim claim, PreparedEffect preparedEffect)
    {
        requireNonNull(request, "request is null");
        requireNonNull(claim, "claim is null");
        requireNonNull(preparedEffect, "preparedEffect is null");
        if (!(preparedEffect instanceof PreparedGitHubEffect prepared)
                || !prepared.request().equals(request)
                || !prepared.claim().equals(claim.spec())) {
            throw new IllegalArgumentException(
                    "prepared merge effect does not match the durable claim");
        }
        if (prepared.evidence() != null) {
            return prepared.evidence();
        }
        try {
            return switch (claim.spec().kind()) {
                case DIRECT_MERGE -> executeDirect(prepared.subject(), request);
                case ENTER_QUEUE -> executeQueue(
                        prepared.subject(), request, prepared.queueNodeId());
            };
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
        MergeResult result = pullRequests.mergePullRequest(
                subject.pat(), subject.ref(),
                new MergePullRequestCommand(
                        request.mergeMethod(), Optional.empty(), Optional.empty(),
                        Optional.of(request.headSha())));
        if (!result.merged() || result.queued()) {
            throw new SubjectRejectedException(
                    "GitHub rejected direct merge for the exact head: "
                            + result.message());
        }
        return new EffectEvidence(
                result.sha(), "direct merge accepted; awaiting RemoteObserver", false);
    }

    private EffectEvidence executeQueue(
            Subject subject, MergeRequest request, String queueNodeId)
    {
        MergeResult result = pullRequests.enqueuePullRequest(
                subject.pat(), queueNodeId, request.headSha());
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

    private static void requireEffectKind(MergeRequest request, EffectKind kind)
    {
        EffectKind expected = request.mode() == MergeMode.MERGE_QUEUE
                ? EffectKind.ENTER_QUEUE : EffectKind.DIRECT_MERGE;
        if (kind != expected) {
            throw new SubjectRejectedException(
                    "merge mode and requested effect kind do not match");
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

    private record PreparedGitHubEffect(
            MergeRequest request,
            ClaimSpec claim,
            Subject subject,
            String queueNodeId,
            EffectEvidence evidence)
            implements PreparedEffect
    {
        private PreparedGitHubEffect
        {
            requireNonNull(request, "request is null");
            requireNonNull(claim, "claim is null");
            requireNonNull(subject, "subject is null");
            if (queueNodeId != null && queueNodeId.isBlank()) {
                throw new IllegalArgumentException("queueNodeId must not be blank");
            }
            if (evidence == null
                    && (claim.kind() == EffectKind.ENTER_QUEUE) != (queueNodeId != null)) {
                throw new IllegalArgumentException(
                        "prepared mutation does not match its effect kind");
            }
        }
    }
}
