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
package com.bytequay.app.developmentflow.execution.provisioning;

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact GitRunner adapter; it contains no branch, path, or remote fallback. */
@Component
public final class GitRunnerProvisioningGit
        implements ProvisionTaskOperationHandler.ProvisioningGit
{
    private final GitRunner git;
    private final CodeFingerprints fingerprints;

    public GitRunnerProvisioningGit(GitRunner git, CodeFingerprints fingerprints)
    {
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
    }

    @Override
    public Probe probe(ProvisionTaskOperationHandler.MutationTarget target)
    {
        requireNonNull(target, "target is null");
        try {
            Path root = target.repositoryRoot();
            Path worktree = target.worktreePath();
            String branch = target.branchName();
            Optional<String> branchHead = git.resolveCommitSha(
                    root, "refs/heads/" + branch);
            boolean pathExists = Files.exists(worktree);
            if (!pathExists && branchHead.isEmpty()) {
                return Probe.absent();
            }
            if (!pathExists || branchHead.isEmpty()) {
                return Probe.conflict("branch and worktree do not both exist");
            }
            if (!git.isGitWorkingTree(worktree)
                    || !git.sharesCommonDirectory(root, worktree)) {
                return Probe.conflict("path is not an exact linked worktree");
            }
            String currentBranch = git.currentBranch(worktree);
            String head = git.headSha(worktree);
            if (!branch.equals(currentBranch)
                    || !branchHead.orElseThrow().equals(head)) {
                return Probe.conflict("worktree branch or HEAD does not match");
            }
            if (!git.statusPorcelainZ(worktree).isEmpty()) {
                return Probe.conflict("provisioning worktree is not clean");
            }
            return Probe.exact(head);
        }
        catch (IOException failure) {
            throw new IllegalStateException("probing exact provisioning target failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "probing exact provisioning target was interrupted", failure);
        }
    }

    @Override
    public String requireExactRemote(Path repositoryRoot, String repositoryId)
    {
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireText(repositoryId, "repositoryId");
        try {
            List<String> matches = new ArrayList<>();
            for (GitRunner.Remote remote : git.listRemotes(repositoryRoot)) {
                Optional<RepoRef> slug = git.remoteSlug(repositoryRoot, remote.name());
                if (slug.isPresent()
                        && slug.orElseThrow().fullName().equalsIgnoreCase(repositoryId)) {
                    matches.add(remote.name());
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "expected one exact configured remote for " + repositoryId
                                + ", found " + matches.size());
            }
            return matches.getFirst();
        }
        catch (IOException failure) {
            throw new IllegalStateException("reading configured Git remotes failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading Git remotes was interrupted", failure);
        }
    }

    @Override
    public Optional<String> resolveCommit(Path repositoryRoot, String ref)
    {
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireText(ref, "ref");
        try {
            return git.resolveCommitSha(repositoryRoot, ref);
        }
        catch (IOException failure) {
            throw new IllegalStateException("resolving exact local commit failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("resolving local commit was interrupted", failure);
        }
    }

    @Override
    public Optional<String> resolveRemoteBranch(
            Path repositoryRoot, String remote, String branch)
    {
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireText(remote, "remote");
        requireText(branch, "branch");
        try {
            if (!git.isValidBranchName(branch)) {
                throw new IllegalStateException("frozen remote branch name is invalid");
            }
            return git.resolveCommitSha(
                    repositoryRoot, "refs/remotes/" + remote + "/" + branch);
        }
        catch (IOException failure) {
            throw new IllegalStateException("resolving exact remote branch failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("resolving remote branch was interrupted", failure);
        }
    }

    @Override
    public void fetchRemote(
            ProvisionTaskOperationHandler.MutationTarget target,
            String remote,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireFence(target, fence);
        requireText(remote, "remote");
        try {
            git.fetchRemote(target.repositoryRoot(), remote);
        }
        catch (IOException failure) {
            throw new MutationFailure("exact remote fetch failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new MutationFailure("exact remote fetch was interrupted", failure);
        }
        catch (RuntimeException failure) {
            throw new MutationFailure("exact remote fetch failed", failure);
        }
    }

    @Override
    public void createWorktree(
            ProvisionTaskOperationHandler.MutationTarget target,
            String headSha,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireFence(target, fence);
        requireText(headSha, "headSha");
        try {
            if (!git.isValidBranchName(target.branchName())) {
                throw new IllegalArgumentException("frozen Task branch name is invalid");
            }
            git.worktreeAdd(
                    target.repositoryRoot(), target.worktreePath(),
                    target.branchName(), headSha);
        }
        catch (IOException failure) {
            throw new MutationFailure("exact worktree creation failed", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new MutationFailure("exact worktree creation was interrupted", failure);
        }
        catch (RuntimeException failure) {
            throw new MutationFailure("exact worktree creation failed", failure);
        }
    }

    @Override
    public String codeFingerprint(ProvisionTaskOperationHandler.MutationTarget target)
    {
        requireNonNull(target, "target is null");
        return fingerprints.fingerprint(target.worktreePath());
    }

    private static void requireFence(
            ProvisionTaskOperationHandler.MutationTarget target,
            WorktreeWriterLeaseManager.MutationFence fence)
    {
        requireNonNull(target, "target is null");
        requireNonNull(fence, "fence is null");
        if (!target.worktreePath().toString().equals(fence.worktreePath())
                || !target.taskId().equals(fence.taskId())
                || target.taskEpoch() != fence.taskEpoch()
                || !target.operationId().equals(fence.operationId())) {
            throw new IllegalStateException(
                    "Git mutation does not hold the exact provisioning writer fence");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
