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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Safe, local-only cherry-picks executed in an isolated worktree. */
@Service
public class WorkspaceCherryPickService
{
    private static final int HISTORY_LIMIT = 5_000;
    private static final int MAX_COMMITS = 100;
    private static final String RESULT_BRANCH_PREFIX = "cherry-pick/";
    private static final Pattern OPERATION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final WorkspaceRepositoryResolver resolver;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;

    public WorkspaceCherryPickService(
            WorkspaceRepositoryResolver resolver,
            WatchedRepoStore watchedRepos,
            GitRunner git)
    {
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
    }

    public CherryPickResult cherryPick(
            String workspaceId,
            String sourceBranch,
            String targetBranch,
            List<String> requestedShas)
            throws IOException, InterruptedException
    {
        requireText(sourceBranch, "sourceBranch");
        requireText(targetBranch, "targetBranch");
        if (requestedShas == null || requestedShas.isEmpty()
                || requestedShas.size() > MAX_COMMITS) {
            throw new IllegalArgumentException(
                    "choose between 1 and " + MAX_COMMITS + " commits");
        }
        Path main = clonePath(workspaceId);
        String sourceRef = resolveRef(main, sourceBranch);
        String targetRef = resolveRef(main, targetBranch);
        List<String> ordered = contiguousOldestFirst(
                main, sourceRef, requestedShas);

        String operationId = UUID.randomUUID().toString();
        String resultBranch = uniqueResultBranch(
                main, targetBranch, operationId);
        Path worktree = worktreePath(main, operationId);
        git.worktreeAdd(main, worktree, resultBranch, targetRef);

        GitRunner.CherryPickOutcome outcome =
                git.cherryPick(worktree, ordered);
        if (outcome.complete()) {
            String retainedPath = null;
            String note = null;
            try {
                git.worktreeRemove(main, worktree);
            }
            catch (IOException | RuntimeException e) {
                retainedPath = worktree.toString();
                note = "Cherry-pick completed, but the temporary checkout "
                        + "could not be removed: " + e.getMessage();
            }
            return new CherryPickResult(
                    operationId,
                    "done",
                    resultBranch,
                    targetRef,
                    ordered,
                    outcome.appliedCount(),
                    List.of(),
                    retainedPath,
                    null,
                    null,
                    null,
                    note);
        }

        String message = "Cherry-pick has conflicts. Abort to undo it, or "
                + "resolve it yourself in the retained worktree: " + worktree;
        return new CherryPickResult(
                operationId,
                "conflicted",
                resultBranch,
                targetRef,
                ordered,
                outcome.appliedCount(),
                outcome.conflictPaths(),
                worktree.toString(),
                null,
                null,
                null,
                message);
    }

    /**
     * Undoes a conflicted cherry-pick: aborts the in-progress sequencer,
     * drops the isolated worktree, and deletes the throwaway branch it was
     * building. Nothing outside the worktree was ever touched, so this
     * leaves the clone exactly as it was before the pick.
     *
     * <p>The worktree is rebuilt from {@code operationId} rather than taken
     * from the caller, and the branch is read out of the worktree's own
     * HEAD — a client-supplied path or branch name would be a way to make
     * this delete something it doesn't own.
     */
    public CherryPickResult abort(String workspaceId, String operationId)
            throws IOException, InterruptedException
    {
        requireText(operationId, "operationId");
        // Rejects any traversal or shell-ish component before it reaches a path.
        if (!OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId is not a cherry-pick id");
        }
        Path main = clonePath(workspaceId);
        Path worktree = worktreePath(main, operationId);
        if (!Files.isDirectory(worktree)) {
            throw new IllegalArgumentException(
                    "no retained cherry-pick worktree for " + operationId);
        }
        String branch = git.currentBranch(worktree);
        git.abortInProgressOperationForRepair(worktree);
        git.worktreeRemove(main, worktree);
        // Result branches are always namespaced; anything else means we are
        // not looking at a branch this service created, so leave it alone.
        boolean branchRemoved = branch != null && branch.startsWith(RESULT_BRANCH_PREFIX);
        if (branchRemoved) {
            git.deleteBranches(main, List.of(branch));
        }
        return new CherryPickResult(
                operationId,
                "aborted",
                branchRemoved ? branch : null,
                null,
                List.of(),
                0,
                List.of(),
                null,
                null,
                null,
                null,
                "Cherry-pick aborted. The worktree was removed and the clone "
                        + "is unchanged.");
    }

    private Path clonePath(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        WatchedRepo watched = watchedRepos.find(repo.owner(), repo.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "workspace repository is not watched"));
        if (watched.localClonePath() == null
                || watched.localClonePath().isBlank()) {
            throw new IllegalStateException(
                    "workspace repository has no local clone");
        }
        return Path.of(watched.localClonePath()).toAbsolutePath().normalize();
    }

    private static Path worktreePath(Path main, String operationId)
    {
        return main.resolveSibling(main.getFileName() + ".bytequay-worktrees")
                .resolve("cherry-pick")
                .resolve(operationId)
                .toAbsolutePath().normalize();
    }

    private List<String> contiguousOldestFirst(
            Path main,
            String sourceRef,
            List<String> requested)
            throws IOException, InterruptedException
    {
        List<GitRunner.CommitEntry> history =
                git.listCommits(main, sourceRef, HISTORY_LIMIT);
        List<String> full = new ArrayList<>(requested.size());
        Set<String> dedup = new HashSet<>();
        for (String requestedSha : requested) {
            requireText(requestedSha, "sha");
            String resolved = git.resolveCommitSha(main, requestedSha)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown commit: " + requestedSha));
            if (!dedup.add(resolved)) {
                throw new IllegalArgumentException(
                        "commit selection contains duplicates");
            }
            full.add(resolved);
        }
        List<Integer> positions = full.stream()
                .map(sha -> indexOf(history, sha))
                .sorted()
                .toList();
        if (positions.contains(-1)) {
            throw new IllegalArgumentException(
                    "every selected commit must belong to the source branch");
        }
        if (positions.getLast() - positions.getFirst() + 1
                != positions.size()) {
            throw new IllegalArgumentException(
                    "selected commits must be a contiguous displayed range");
        }
        List<String> ordered = new ArrayList<>(positions.size());
        for (int index = positions.getLast();
                index >= positions.getFirst(); index--) {
            ordered.add(history.get(index).sha());
        }
        return List.copyOf(ordered);
    }

    private static int indexOf(
            List<GitRunner.CommitEntry> history,
            String sha)
    {
        for (int i = 0; i < history.size(); i++) {
            if (sha.equals(history.get(i).sha())) {
                return i;
            }
        }
        return -1;
    }

    private String resolveRef(Path main, String branch)
            throws IOException, InterruptedException
    {
        if (git.refExists(main, branch)) {
            return branch;
        }
        String remote = "origin/" + branch;
        if (git.refExists(main, remote)) {
            return remote;
        }
        throw new IllegalArgumentException(
                "branch is not available locally: " + branch);
    }

    private String uniqueResultBranch(
            Path main,
            String targetBranch,
            String operationId)
            throws IOException, InterruptedException
    {
        String slug = targetBranch.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "target";
        }
        if (slug.length() > 32) {
            slug = slug.substring(0, 32);
        }
        String base = "cherry-pick/" + slug + "-"
                + operationId.substring(0, 8);
        String candidate = base;
        int suffix = 2;
        while (git.refExists(main, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record CherryPickResult(
            String operationId,
            String status,
            String resultBranch,
            String targetRef,
            List<String> commits,
            int appliedCount,
            List<String> conflictPaths,
            String worktreePath,
            String trunkId,
            String taskId,
            String sessionId,
            String message) {}
}
