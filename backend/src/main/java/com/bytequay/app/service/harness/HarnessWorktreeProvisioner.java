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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Resolves only app-owned harness checkouts. A manual watch receives a
 * detached linked worktree so no harness phase can edit the registered clone.
 */
@Component
public class HarnessWorktreeProvisioner
{
    private static final String ORIGIN = "origin";

    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;

    public HarnessWorktreeProvisioner(WatchedRepoStore watchedRepos, GitRunner git)
    {
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
    }

    public Path prepare(
            String owner,
            String repo,
            String requestedLocalPath,
            String watchId,
            String branch)
    {
        Path main = registeredCheckout(owner, repo);
        Path requested = requestedLocalPath == null || requestedLocalPath.isBlank()
                ? main
                : Path.of(requestedLocalPath).toAbsolutePath().normalize();
        try {
            if (requested.equals(main) || requested.toRealPath().equals(main)) {
                return createDetached(main, watchId, branch);
            }
            return requireExistingAppWorktree(main, requested, watchId, branch);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Harness worktree validation was interrupted", e);
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to resolve harness checkout: " + e.getMessage(), e);
        }
    }

    private Path registeredCheckout(String owner, String repo)
    {
        String mapped = watchedRepos.find(owner, repo)
                .map(WatchedRepo::localClonePath)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        owner + "/" + repo + " has no mapped local checkout"));
        try {
            Path main = Path.of(mapped).toRealPath();
            requireGitCheckout(main, false);
            return main;
        }
        catch (IOException e) {
            throw new IllegalStateException("Registered checkout is unavailable: " + mapped, e);
        }
    }

    private Path createDetached(Path main, String watchId, String branch)
    {
        requireSafeSegment(watchId, "watchId");
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch is required to provision a harness worktree");
        }
        Path appRoot = appWorktreeRoot(main);
        Path harnessRoot = appRoot.resolve("ci-harness");
        Path target = harnessRoot.resolve(watchId).normalize();
        if (!target.getParent().equals(harnessRoot)) {
            throw new IllegalStateException("Harness worktree path is already occupied: " + target);
        }
        try {
            String baseRef = remoteBranchRef(main, branch);
            String expectedHead = git.resolveCommitSha(main, baseRef).orElseThrow();
            requireOwnedDirectory(appRoot);
            requireOwnedDirectory(harnessRoot);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return adoptExistingHarnessWorktree(main, target, expectedHead);
            }
            try {
                git.worktreeAddDetached(main, target, baseRef);
                return requireCleanDetachedHarnessWorktree(main, target, expectedHead);
            }
            catch (IOException | InterruptedException | RuntimeException failure) {
                removeFailedWorktree(main, target, failure);
                throw failure;
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Harness worktree provisioning was interrupted", e);
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to provision harness worktree: " + e.getMessage(), e);
        }
    }

    private Path requireExistingAppWorktree(
            Path main, Path requested, String watchId, String branch)
            throws IOException, InterruptedException
    {
        requireSafeSegment(watchId, "watchId");
        Path appRoot = appWorktreeRoot(main);
        requireGitCheckout(requested, true);
        Path real = requested.toRealPath();
        Path parent = real.getParent();
        boolean harnessWorktree = parent != null
                && parent.equals(appRoot.resolve("ci-harness"))
                && real.getFileName().toString().equals(watchId);
        boolean expected = parent != null
                && (parent.equals(appRoot.resolve("cherry-pick"))
                        || parent.equals(appRoot.resolve("upstream-cherry-pick"))
                        || harnessWorktree);
        if (!expected) {
            throw new IllegalArgumentException(
                    "localPath must be an exact app-owned harness or cherry-pick worktree");
        }
        if (!Files.isDirectory(appRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().getParent().equals(appRoot.toRealPath())) {
            throw new IllegalArgumentException("localPath escapes its app-owned worktree root");
        }
        if (!real.getParent().equals(parent.toRealPath())) {
            throw new IllegalArgumentException("localPath escapes its app-owned worktree root");
        }
        if (harnessWorktree) {
            if (!git.sharesCommonDirectory(main, real)) {
                throw new IllegalArgumentException(
                        "localPath is not linked to the workspace repository");
            }
            String expectedHead = git.resolveCommitSha(main, remoteBranchRef(main, branch))
                    .orElseThrow();
            return requireCleanDetachedHarnessWorktree(main, real, expectedHead);
        }
        return real;
    }

    private String remoteBranchRef(Path main, String branch)
            throws IOException, InterruptedException
    {
        if (!git.isValidBranchName(branch)) {
            throw new IllegalArgumentException("branch is not a valid git branch name");
        }
        git.fetchRemote(main, ORIGIN);
        String ref = "refs/remotes/" + ORIGIN + "/" + branch;
        if (git.resolveCommitSha(main, ref).isEmpty()) {
            throw new IllegalStateException(
                    "PR head branch is unavailable after fetch: " + branch);
        }
        return ref;
    }

    private Path adoptExistingHarnessWorktree(Path main, Path target, String expectedHead)
            throws IOException, InterruptedException
    {
        try {
            return requireCleanDetachedHarnessWorktree(main, target, expectedHead);
        }
        catch (RuntimeException | IOException failure) {
            throw new IllegalStateException(
                    "Existing harness worktree cannot be safely adopted: " + target, failure);
        }
    }

    private Path requireCleanDetachedHarnessWorktree(
            Path main, Path target, String expectedHead)
            throws IOException, InterruptedException
    {
        requireGitCheckout(target, true);
        if (!git.sharesCommonDirectory(main, target)
                || git.currentBranch(target) != null
                || git.hasUncommittedChanges(target)
                || !expectedHead.equals(git.headSha(target))) {
            throw new IllegalStateException(
                    "Harness worktree is not a clean detached checkout at the fetched PR head");
        }
        return target.toRealPath();
    }

    private static void requireGitCheckout(Path root, boolean linked)
    {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Harness checkout is unavailable: " + root);
        }
        Path dotGit = root.resolve(".git");
        boolean valid = linked
                ? Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)
                : Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)
                        || Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS);
        if (!valid) {
            throw new IllegalStateException("Harness checkout is not a git worktree: " + root);
        }
    }

    private void removeFailedWorktree(Path main, Path target, Throwable failure)
    {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            git.worktreeRemove(main, target);
        }
        catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
        catch (InterruptedException cleanup) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(cleanup);
        }
    }

    private static Path appWorktreeRoot(Path main)
    {
        return main.resolveSibling(main.getFileName() + ".bytequay-worktrees").normalize();
    }

    private static void requireOwnedDirectory(Path directory)
            throws IOException
    {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "App-owned worktree directory is not a real directory: " + directory);
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "App-owned worktree directory is unavailable: " + directory);
        }
    }

    private static void requireSafeSegment(String value, String name)
    {
        if (value == null || ".".equals(value) || "..".equals(value)
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
