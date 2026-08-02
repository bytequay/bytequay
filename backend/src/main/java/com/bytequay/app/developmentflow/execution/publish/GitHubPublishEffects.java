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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.AmbiguousEffectException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.BaseMovedException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.MissingEffectException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRequest;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RemoteReference;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RetryableEffectException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.SubjectRejectedException;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Fixed Git/GitHub implementation of the six publish effects. */
@Component
public final class GitHubPublishEffects
        implements PublishOperationHandler.PublishEffects
{
    private final GitRunner git;
    private final CodeFingerprints fingerprints;
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;

    public GitHubPublishEffects(
            GitRunner git,
            CodeFingerprints fingerprints,
            PullRequestRepository pullRequests,
            PatResolver pats)
    {
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    @Override
    public EffectEvidence verifySubject(PublishRequest request)
    {
        requireNonNull(request, "request is null");
        Path worktree = worktree(request);
        try {
            if (!Files.isDirectory(worktree) || !git.isGitWorkingTree(worktree)) {
                throw rejected("publish worktree is missing or is not a Git worktree");
            }
            if (!request.branchName().equals(git.currentBranch(worktree))) {
                throw rejected("publish worktree is not on its frozen branch");
            }
            if (!request.expectedHeadSha().equals(git.headSha(worktree))) {
                throw rejected("publish HEAD differs from its authorization");
            }
            if (!git.statusPorcelainZ(worktree).isEmpty()) {
                throw rejected("publish worktree contains uncommitted changes");
            }
            if (!request.codeFingerprint().equals(fingerprints.fingerprint(worktree))) {
                throw rejected("publish fingerprint differs from its authorization");
            }
            Integer ahead = git.commitCountUniqueTo(
                    worktree, "HEAD", request.expectedBaseSha());
            if (ahead == null || ahead < 1) {
                throw rejected("publish branch is not provably ahead of its frozen base");
            }
            return EffectEvidence.local(
                    EffectKind.VERIFY_SUBJECT,
                    "clean exact branch at " + request.expectedHeadSha());
        }
        catch (SubjectRejectedException e) {
            throw e;
        }
        catch (IOException e) {
            throw retryable("inspecting the exact publish subject failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable("inspecting the publish subject was interrupted", e);
        }
    }

    @Override
    public EffectEvidence verifyBranchBase(PublishRequest request)
    {
        requireNonNull(request, "request is null");
        Path worktree = worktree(request);
        try {
            String pushRemote = requireExactRemote(
                    worktree, request.publishRepositoryId());
            if (!"origin".equals(pushRemote)) {
                throw rejected("frozen publish repository is not the origin push target");
            }
            String baseRemote = requireExactRemote(
                    worktree, request.baseRepositoryId());
            String remoteBase = git.remoteHeadSha(
                            worktree, baseRemote, request.baseBranch())
                    .orElseThrow(() -> rejected(
                            "frozen remote base branch does not exist"));
            if (!request.expectedBaseSha().equals(remoteBase)) {
                throw new BaseMovedException(remoteBase);
            }
            if (!request.branchName().equals(git.currentBranch(worktree))
                    || !request.expectedHeadSha().equals(git.headSha(worktree))) {
                throw rejected("local branch moved during publish reconciliation");
            }
            return EffectEvidence.local(
                    EffectKind.RECONCILE_BRANCH_BASE,
                    "origin and remote base match the frozen route");
        }
        catch (SubjectRejectedException e) {
            throw e;
        }
        catch (IOException e) {
            throw retryable("verifying the publish route failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable("verifying the publish route was interrupted", e);
        }
    }

    @Override
    public EffectEvidence probePushedBranch(PublishRequest request)
    {
        try {
            return requirePushedBranch(request, true);
        }
        catch (RetryableEffectException failure) {
            throw new AmbiguousEffectException(
                    "remote branch probe could not determine the prior push", failure);
        }
    }

    @Override
    public EffectEvidence pushOrAdoptBranch(
            PublishRequest request,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireMutationFence(request, fence);
        Optional<EffectEvidence> prior = findPushedBranch(request);
        if (prior.isPresent()) {
            return prior.orElseThrow();
        }
        try {
            git.push(worktree(request));
        }
        catch (IOException | RuntimeException failure) {
            return recoverPush(request, failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return recoverPush(request, failure);
        }
        return requirePushedBranch(request, false);
    }

    @Override
    public EffectEvidence probeDraftPullRequest(PublishRequest request)
    {
        try {
            return findExactPullRequest(request)
                    .map(remote -> EffectEvidence.remote(
                            EffectKind.CREATE_OR_ADOPT_DRAFT_PR,
                            "adopted exact existing Draft PR", remote))
                    .orElseThrow(() -> new MissingEffectException(
                            "exact Draft PR is not present"));
        }
        catch (MissingEffectException | SubjectRejectedException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new AmbiguousEffectException(
                    "Draft PR probe could not determine the prior create", e);
        }
    }

    @Override
    public EffectEvidence createOrAdoptDraftPullRequest(
            PublishRequest request,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireMutationFence(request, fence);
        Optional<RemoteReference> prior = findExactPullRequest(request);
        if (prior.isPresent()) {
            return EffectEvidence.remote(
                    EffectKind.CREATE_OR_ADOPT_DRAFT_PR,
                    "adopted exact existing Draft PR", prior.orElseThrow());
        }
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        String pat = pats.resolve(repository.fullName());
        try {
            PullRequest opened = pullRequests.createPullRequest(
                    pat,
                    repository,
                    CreatePullRequestCommand.draft(
                            request.headRef(), request.baseBranch(),
                            request.prTitle(), request.prBody()));
            if (opened == null) {
                throw new IllegalStateException(
                        "GitHub returned no created pull request");
            }
            RemoteReference exact = requireExactPullRequest(
                    request, pat, repository, opened);
            return EffectEvidence.remote(
                    EffectKind.CREATE_OR_ADOPT_DRAFT_PR,
                    "created exact Draft PR", exact);
        }
        catch (SubjectRejectedException conflict) {
            throw conflict;
        }
        catch (RuntimeException createFailure) {
            try {
                Optional<RemoteReference> recovered = findExactPullRequest(request);
                if (recovered.isPresent()) {
                    return EffectEvidence.remote(
                            EffectKind.CREATE_OR_ADOPT_DRAFT_PR,
                            "adopted Draft PR after ambiguous create",
                            recovered.orElseThrow());
                }
            }
            catch (SubjectRejectedException conflict) {
                throw conflict;
            }
            catch (RuntimeException probeFailure) {
                createFailure.addSuppressed(probeFailure);
            }
            throw new AmbiguousEffectException(
                    "Draft PR create did not leave an immediately provable result",
                    createFailure);
        }
    }

    @Override
    public EffectEvidence fetchRemoteDetail(
            PublishRequest request, RemoteReference remote)
    {
        RemoteReference exact = fetchExactRemote(request, remote);
        return EffectEvidence.remote(
                EffectKind.FETCH_REMOTE_DETAIL,
                "fetched exact remote Draft PR detail", exact);
    }

    @Override
    public EffectEvidence proveRemoteHead(
            PublishRequest request, RemoteReference remote)
    {
        RemoteReference exact = fetchExactRemote(request, remote);
        if (!request.expectedHeadSha().equals(exact.headSha())
                || !request.expectedBaseSha().equals(exact.baseSha())) {
            throw rejected("remote PR head/base differs from publish authorization");
        }
        return EffectEvidence.remote(
                EffectKind.PROVE_REMOTE_HEAD,
                "remote Draft PR proves exact authorized head and base", exact);
    }

    private EffectEvidence recoverPush(PublishRequest request, Throwable failure)
    {
        try {
            Optional<EffectEvidence> recovered = findPushedBranch(request);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            throw new AmbiguousEffectException(
                    "push failed and the remote branch is not yet observable", failure);
        }
        catch (SubjectRejectedException conflict) {
            throw conflict;
        }
        catch (AmbiguousEffectException ambiguous) {
            throw ambiguous;
        }
        catch (RuntimeException probeFailure) {
            failure.addSuppressed(probeFailure);
            throw new AmbiguousEffectException(
                    "push outcome could not be reconciled", failure);
        }
    }

    private EffectEvidence requirePushedBranch(
            PublishRequest request, boolean missingIsExpected)
    {
        Optional<EffectEvidence> evidence = findPushedBranch(request);
        if (evidence.isPresent()) {
            return evidence.orElseThrow();
        }
        String message = "remote branch does not contain the authorized head";
        if (missingIsExpected) {
            throw new MissingEffectException(message);
        }
        throw new AmbiguousEffectException(message);
    }

    private Optional<EffectEvidence> findPushedBranch(PublishRequest request)
    {
        Path worktree = worktree(request);
        try {
            String remote = requireExactRemote(
                    worktree, request.publishRepositoryId());
            if (!"origin".equals(remote)) {
                throw rejected("frozen publish repository is not origin");
            }
            Optional<String> observed = git.remoteHeadSha(
                    worktree, remote, request.branchName());
            if (observed.isEmpty()) {
                return Optional.empty();
            }
            if (!request.expectedHeadSha().equals(observed.orElseThrow())) {
                throw rejected("remote branch contains a different head");
            }
            return Optional.of(EffectEvidence.local(
                    EffectKind.PUSH_BRANCH,
                    "remote branch contains " + request.expectedHeadSha()));
        }
        catch (SubjectRejectedException e) {
            throw e;
        }
        catch (IOException e) {
            throw retryable("probing the remote branch failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable("probing the remote branch was interrupted", e);
        }
    }

    private Optional<RemoteReference> findExactPullRequest(PublishRequest request)
    {
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        RepoRef headRepository = RepoRef.parse(request.headRepositoryId());
        String pat = pats.resolve(repository.fullName());
        ListPullRequestsQuery query = new ListPullRequestsQuery(
                "open",
                Optional.of(headRepository.owner() + ":" + request.branchName()),
                Optional.of(request.baseBranch()),
                "created",
                "desc",
                10,
                1);
        List<PullRequest> matches = pullRequests
                .listPullRequests(pat, repository, query).stream()
                .filter(candidate -> repository.fullName()
                        .equalsIgnoreCase(candidate.repo()))
                .filter(candidate -> "open".equalsIgnoreCase(candidate.state()))
                .filter(candidate -> request.branchName().equals(candidate.headRef()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            throw rejected("multiple open PRs match the frozen head/base route");
        }
        return Optional.of(requireExactPullRequest(
                request, pat, repository, matches.getFirst()));
    }

    private RemoteReference requireExactPullRequest(
            PublishRequest request,
            String pat,
            RepoRef repository,
            PullRequest candidate)
    {
        if (!repository.fullName().equalsIgnoreCase(candidate.repo())
                || candidate.number() < 1
                || candidate.htmlUrl() == null || candidate.htmlUrl().isBlank()
                || !request.prTitle().equals(candidate.title())
                || !candidate.draft()
                || !"open".equalsIgnoreCase(candidate.state())
                || !request.branchName().equals(candidate.headRef())) {
            throw rejected("remote PR identity or approved metadata differs");
        }
        PullRequestRef ref = PullRequestRef.of(
                repository.owner(), repository.repo(), candidate.number());
        PrRawDetail detail = pullRequests.fetchPrDetail(pat, ref);
        if (detail == null
                || !normalizeBody(request.prBody()).equals(
                        normalizeBody(detail.body()))
                || !detail.draft()
                || !request.expectedHeadSha().equals(detail.headSha())
                || !request.branchName().equals(detail.headRef())
                || !request.headRepositoryId().equalsIgnoreCase(detail.headRepo())
                || !request.baseBranch().equals(detail.baseRef())
                || !request.baseRepositoryId().equalsIgnoreCase(detail.baseRepo())
                || !"open".equalsIgnoreCase(detail.state())
                || detail.merged()
                || !request.expectedBaseSha().equals(detail.baseSha())) {
            throw rejected("remote Draft PR does not match the frozen publish subject");
        }
        return new RemoteReference(
                repository.fullName(), candidate.number(), candidate.htmlUrl(),
                request.headRef(), detail.headSha(), detail.baseSha());
    }

    private RemoteReference fetchExactRemote(
            PublishRequest request, RemoteReference expected)
    {
        requireNonNull(expected, "expected remote is null");
        if (!request.baseRepositoryId().equalsIgnoreCase(expected.repositoryId())
                || !request.headRef().equals(expected.headRef())) {
            throw rejected("stored remote PR reference differs from frozen route");
        }
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        String pat = pats.resolve(repository.fullName());
        try {
            PullRequest current = pullRequests.getPullRequest(
                    pat, PullRequestRef.of(
                            repository.owner(), repository.repo(), expected.number()));
            return requireExactPullRequest(request, pat, repository, current);
        }
        catch (SubjectRejectedException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw retryable("fetching exact remote PR detail failed", e);
        }
    }

    private String requireExactRemote(Path worktree, String repositoryId)
            throws IOException, InterruptedException
    {
        List<String> matches = new ArrayList<>();
        for (GitRunner.Remote remote : git.listRemotes(worktree)) {
            Optional<RepoRef> slug = git.remoteSlug(worktree, remote.name());
            if (slug.isPresent()
                    && repositoryId.equalsIgnoreCase(
                            slug.orElseThrow().fullName())) {
                matches.add(remote.name());
            }
        }
        if (matches.size() != 1) {
            throw rejected("expected one exact Git remote for "
                    + repositoryId + ", found " + matches.size());
        }
        return matches.getFirst();
    }

    private static void requireMutationFence(
            PublishRequest request,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireNonNull(fence, "fence is null");
        if (!worktree(request).toString().equals(
                    Path.of(fence.worktreePath()).toAbsolutePath().normalize().toString())
                || !request.taskId().equals(fence.taskId())
                || !request.operationId().equals(fence.operationId())
                || request.taskEpoch() != fence.taskEpoch()) {
            throw new IllegalStateException(
                    "publish mutation fence does not match its exact Task worktree");
        }
    }

    private static Path worktree(PublishRequest request)
    {
        return Path.of(request.worktreePath()).toAbsolutePath().normalize();
    }

    private static String normalizeBody(String body)
    {
        return body == null ? "" : body;
    }

    private static SubjectRejectedException rejected(String message)
    {
        return new SubjectRejectedException(message);
    }

    private static RetryableEffectException retryable(
            String message, Throwable cause)
    {
        return new RetryableEffectException(message, cause);
    }
}
