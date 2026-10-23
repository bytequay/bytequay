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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Git evidence and checked source synchronization for the live development coordinator. */
public class DevelopmentGitInspector
{
    private final WorktreeService worktrees;
    private final WorkspaceStore workspaces;
    private final GitCommandRunner git;
    private final CredentialService credentials;
    private final GitHubPullRequestReadClient pullRequests;
    private final PullRequestTaskStore assignments;
    private final PullRequestService cachedPullRequests;

    public DevelopmentGitInspector(
            WorktreeService worktrees,
            WorkspaceStore workspaces,
            GitCommandRunner git,
            CredentialService credentials,
            GitHubPullRequestReadClient pullRequests,
            PullRequestTaskStore assignments,
            PullRequestService cachedPullRequests)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.cachedPullRequests = requireNonNull(cachedPullRequests, "cachedPullRequests is null");
    }

    /** Captures only local Git and persisted workspace routing; no fetch or GitHub read occurs. */
    public DevelopmentBaseline capture(AgentTask task)
    {
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new IllegalStateException("Development task has no managed worktree"));
        WorktreeSnapshot snapshot = worktrees.inspect(worktree);
        Workspace workspace = workspace(worktree);
        String sourceRemote = workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin";
        Optional<PullRequestRef> assigned = assignedPullRequest(worktree);
        Optional<PullRequestDetail> detail = assigned.map(pr -> cachedPullRequests.findPullRequestDetail(pr.repoFullName(), pr.number())
                .orElseThrow(() -> new IllegalStateException("Assigned pull request details are unavailable; reload the PR before continuing")));
        String baseBranch = detail.map(pr -> Optional.ofNullable(pr.baseRef())
                        .orElseThrow(() -> new IllegalStateException("Assigned pull request base is unavailable; reload the PR before continuing")))
                .orElseGet(() -> workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()));
        String headBranch = detail.map(pr -> Optional.ofNullable(pr.headRef())
                        .orElseThrow(() -> new IllegalStateException("Assigned pull request head is unavailable; reload the PR before continuing")))
                .orElse(worktree.generatedBranch());
        String baseSha = output(worktree, "rev-parse", "--verify", "refs/remotes/" + sourceRemote + "/" + baseBranch + "^{commit}").strip();
        checkSha(baseSha, "sha");
        DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(
                workspace.selectedRepository(), workspace.monitoredRepository(), workspace.originCloneUrl(), sourceRemote,
                baseBranch, baseSha, headBranch, assigned);
        return new DevelopmentBaseline(worktree, route, inspectLocal(worktree, route, snapshot));
    }

    public DevelopmentLocalFacts inspectLocal(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        return inspectLocal(worktree, route, worktrees.inspect(worktree));
    }

    private DevelopmentLocalFacts inspectLocal(TaskWorktree worktree, DevelopmentPublicationRoute route, WorktreeSnapshot snapshot)
    {
        verify(worktree, route, snapshot);
        requireCommit(worktree, route.baseSha());
        String tree = output(worktree, "rev-parse", "--verify", "HEAD^{tree}").strip();
        checkSha(tree, "sha");
        String status = status(worktree);
        StatusCounts counts = countStatus(status);
        Hasher digest = Hashing.sha256().newHasher();
        add(digest, snapshot.head());
        add(digest, tree);
        // Branch identity is verified separately; a descriptive rename does not change code.
        add(digest, status);
        add(digest, output(worktree, "diff", "--binary", "--no-ext-diff", "--no-textconv", "--cached", "--"));
        add(digest, output(worktree, "diff", "--binary", "--no-ext-diff", "--no-textconv", "--"));
        for (String untracked : counts.untrackedPaths()) {
            add(digest, untracked);
            hashUntracked(digest, worktree.canonicalWorktree(), untracked);
        }
        long commits;
        try {
            commits = Long.parseLong(output(worktree, "rev-list", "--count", route.baseSha() + ".." + snapshot.head()).strip());
        }
        catch (NumberFormatException invalid) {
            throw new AgentStorageException("Git returned an invalid development commit count", invalid);
        }
        String mergeBase = output(worktree, "merge-base", "--all", route.baseSha(), snapshot.head()).strip();
        checkSha(mergeBase, "sha");
        Result diff = git.run(worktree.canonicalWorktree(), "diff", "--quiet", "--no-ext-diff", "--no-textconv", mergeBase, snapshot.head(), "--");
        checkState(!diff.truncated() && (diff.exitCode() == 0 || diff.exitCode() == 1), "Cannot inspect the committed development diff");
        WorktreeSnapshot after = worktrees.inspect(worktree);
        checkState(after.head().equals(snapshot.head()) && status(worktree).equals(status), "Development files changed during inspection");
        return new DevelopmentLocalFacts(
                worktree.generatedBranch(),
                snapshot.head(),
                digest.hash().toString(),
                counts.total(),
                commits,
                diff.exitCode() == 1,
                snapshot.gitOperationInProgress() || after.gitOperationInProgress() || counts.unmerged());
    }

    public DevelopmentPublicationFacts inspectPublication(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        verify(worktree, route);
        DevelopmentPublicationFacts facts = readPublication(worktree, route);
        verify(worktree, route);
        return facts;
    }

    /** Renames only an unpublished branch, recording intent before Git so an uncertain save can be reconciled. */
    public TaskWorktree renameBranch(DevelopmentBaseline baseline, String branch, Consumer<DevelopmentBranchRename> recordIntent, Consumer<TaskWorktree> recordRename)
    {
        TaskWorktree worktree = baseline.worktree();
        DevelopmentPublicationRoute route = baseline.route();
        TaskWorktree renamed = new TaskWorktree(worktree.taskId(), worktree.workspaceId(), worktree.physicalRepository(),
                worktree.canonicalWorktree(), branch, worktree.startCommit());
        checkArgument(branch.substring(0, branch.lastIndexOf('/') + 1).equals(route.headBranch().substring(0, route.headBranch().lastIndexOf('/') + 1)),
                "Branch rename must preserve the task namespace");
        if (branch.equals(worktree.generatedBranch())) {
            return worktree;
        }
        WorktreeSnapshot before = verify(worktree, route);
        checkState(!before.gitOperationInProgress(), "Finish the current Git operation before renaming the branch");
        DevelopmentPublicationFacts published = inspectPublication(worktree, route);
        if (published.remoteHeadSha().isPresent() || !published.matchingPullRequests().isEmpty()
                || !output(worktree, "for-each-ref", "--format=%(upstream)", "refs/heads/" + worktree.generatedBranch()).isBlank()) {
            return worktree;
        }
        checkState(output(worktree, "for-each-ref", "--format=%(refname)",
                "refs/heads/" + branch, "refs/remotes/origin/" + branch, "refs/remotes/upstream/" + branch).isBlank(), "The proposed branch name is already in use");
        DevelopmentPublicationRoute renamedRoute = route.withHeadBranch(branch);
        DevelopmentPublicationFacts target = readPublication(worktree, renamedRoute);
        checkState(target.remoteHeadSha().isEmpty() && target.matchingPullRequests().isEmpty(), "The proposed branch name is already in use on GitHub");
        WorktreeSnapshot checked = verify(worktree, route);
        checkState(checked.head().equals(before.head()) && !checked.gitOperationInProgress(), "Task changed while checking its branch name");
        recordIntent.accept(new DevelopmentBranchRename(worktree.generatedBranch(), branch, checked.head()));
        output(worktree, "branch", "-m", branch);
        verify(renamed, renamedRoute);
        // A save may commit before reporting failure. Keep Git at the recorded target and let
        // recovery finish the task row; compensating here could undo a committed rename.
        recordRename.accept(renamed);
        return renamed;
    }

    /** Called only while no provider or other admitted writer can change this checkout. */
    public TaskWorktree recoverRename(TaskWorktree persisted, DevelopmentBranchRename intent, Consumer<TaskWorktree> recordRename)
    {
        checkState(intent.previousBranch().substring(0, intent.previousBranch().lastIndexOf('/') + 1)
                        .equals(intent.proposedBranch().substring(0, intent.proposedBranch().lastIndexOf('/') + 1)),
                "The pending rename changes the task namespace");
        checkState(persisted.generatedBranch().equals(intent.previousBranch()) || persisted.generatedBranch().equals(intent.proposedBranch()),
                "The saved task branch does not match its pending rename");
        TaskWorktree original = new TaskWorktree(persisted.taskId(), persisted.workspaceId(), persisted.physicalRepository(),
                persisted.canonicalWorktree(), intent.previousBranch(), persisted.startCommit());
        TaskWorktree renamed = new TaskWorktree(persisted.taskId(), persisted.workspaceId(), persisted.physicalRepository(),
                persisted.canonicalWorktree(), intent.proposedBranch(), persisted.startCommit());
        Path checkout = worktrees.ownedCheckout(persisted);
        Result branch = git.run(checkout, "symbolic-ref", "--quiet", "--short", "HEAD");
        checkState(branch.successful() && !branch.truncated() && !branch.output().isBlank(), "The pending rename checkout has no verifiable branch");
        String current = branch.output().strip();
        checkState(current.equals(intent.previousBranch()) || current.equals(intent.proposedBranch()), "The pending rename checkout is on an unrelated branch");
        WorktreeSnapshot snapshot = worktrees.inspect(current.equals(intent.previousBranch()) ? original : renamed);
        checkState(snapshot.head().equals(intent.expectedHead()) && !snapshot.gitOperationInProgress(), "The pending rename checkout changed; inspect it before continuing");
        Optional<String> oldRef = localRef(persisted, intent.previousBranch());
        Optional<String> newRef = localRef(persisted, intent.proposedBranch());
        if (current.equals(intent.previousBranch())) {
            checkState(persisted.generatedBranch().equals(intent.previousBranch()) && oldRef.filter(intent.expectedHead()::equals).isPresent() && newRef.isEmpty(),
                    "The pending rename refs do not match the original task");
            return original;
        }
        checkState(oldRef.isEmpty() && newRef.filter(intent.expectedHead()::equals).isPresent(), "The pending rename refs do not match the renamed task");
        if (!persisted.equals(renamed)) {
            recordRename.accept(renamed);
        }
        return renamed;
    }

    private Optional<String> localRef(TaskWorktree worktree, String branch)
    {
        Result result = git.run(worktree.canonicalWorktree(), "rev-parse", "--verify", "--quiet", "refs/heads/" + branch + "^{commit}");
        if (result.exitCode() == 1 && !result.truncated() && result.output().isBlank()) {
            return Optional.empty();
        }
        checkState(result.successful() && !result.truncated(), "Cannot verify the pending rename refs");
        String head = result.output().strip();
        checkSha(head, "head");
        return Optional.of(head);
    }

    private DevelopmentPublicationFacts readPublication(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        Optional<GitHubPullRequestIdentity> assigned = route.assignedPullRequest().map(pr -> {
            GitHubPullRequestIdentity current = pullRequests.fetchPullRequestIdentity(credentials.resolveGitHubToken(worktree.workspaceId()), pr);
            checkState(current.pullRequest().equals(pr) && current.state() == GitHubPullRequestIdentity.State.OPEN
                            && current.headRepository().equals(route.headRepository()) && current.headBranch().equals(route.headBranch())
                            && current.baseRepository().equals(route.baseRepository()) && current.baseBranch().equals(route.baseBranch()),
                    "Assigned pull request destination changed or is unavailable; reload the PR before continuing");
            return current;
        });
        String ref = "refs/heads/" + route.headBranch();
        Result result = git.run(worktree.canonicalWorktree(), Duration.ofSeconds(30),
                credentials.gitAuthenticationEnvironment(worktree.workspaceId(), ImmutableList.of(route.pushUrl())),
                ImmutableList.of("ls-remote", "--exit-code", "--refs", "--", route.pushUrl(), ref));
        checkState(!result.truncated(), "Remote development branch response was truncated");
        Optional<String> remoteHead;
        if (result.exitCode() == 2 && result.output().isBlank()) {
            remoteHead = Optional.empty();
        }
        else {
            checkState(result.successful(), "Cannot verify the remote development branch; check GitHub access");
            String[] values = result.output().strip().split("\\s+", -1);
            checkState(values.length == 2 && values[1].equals(ref), "Remote development branch response was ambiguous");
            checkSha(values[0], "remote SHA");
            remoteHead = Optional.of(values[0]);
        }
        List<GitHubPullRequestIdentity> matches = assigned.map(ImmutableList::of).orElseGet(() -> ImmutableList.copyOf(pullRequests.fetchPullRequestIdentities(
                credentials.resolveGitHubToken(worktree.workspaceId()), route.baseRepository(), route.headRepository(), route.headBranch())));
        return new DevelopmentPublicationFacts(remoteHead, matches);
    }

    /**
     * Pushes one verified development revision to its accepted route under an explicit lease.
     *
     * <p>The lease is the remote SHA the coordinator verified moments earlier; an empty expected
     * value means the remote branch must still be absent. Credentials reach Git through the
     * workspace transport environment, never the command line or the agent.
     *
     * @param worktree the task's leased worktree
     * @param route the accepted publication destination
     * @param localHead exact commit to publish
     * @param expectedRemoteSha remote SHA the lease requires, empty when no remote branch exists
     */
    public void pushDevelopmentHead(TaskWorktree worktree, DevelopmentPublicationRoute route, String localHead, Optional<String> expectedRemoteSha)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(route, "route is null");
        requireNonNull(expectedRemoteSha, "expectedRemoteSha is null");
        requireCommit(worktree, localHead);
        expectedRemoteSha.ifPresent(value -> checkSha(value, "expected remote SHA"));
        verify(worktree, route);
        String ref = "refs/heads/" + route.headBranch();
        Result result = git.run(worktree.canonicalWorktree(), Duration.ofSeconds(120),
                credentials.gitAuthenticationEnvironment(worktree.workspaceId(), ImmutableList.of(route.pushUrl())),
                ImmutableList.of("push", "--porcelain", "--force-with-lease=" + ref + ":" + expectedRemoteSha.orElse(""), "--", route.pushUrl(), localHead + ":" + ref));
        checkState(!result.truncated(), "Remote push response was truncated");
        checkState(result.successful(), "Could not push the development branch: %s", result.output().strip());
        verify(worktree, route);
    }

    /** Moves a clean source to a revision another checkout of the same repository created, such as a CI repair; no fetch occurs. */
    public DevelopmentLocalFacts synchronizeSource(DevelopmentBaseline baseline, String expectedSourceHead, String revision)
    {
        requireNonNull(baseline, "baseline is null");
        checkSha(expectedSourceHead, "sha");
        checkSha(revision, "sha");
        TaskWorktree worktree = baseline.worktree();
        requireSyncSource(baseline, expectedSourceHead);
        checkState(output(worktree, "cat-file", "-t", revision).strip().equals("commit"), "The revision to synchronize is not a commit");
        // Unlike reset --keep, this preserves ignored build output and refuses ignored-path collisions.
        Result updated = git.run(worktree.canonicalWorktree(), "-c", "core.hooksPath=/dev/null", "checkout",
                "--no-overwrite-ignore", "--no-recurse-submodules", "-B", worktree.generatedBranch(), revision);
        checkState(updated.successful() && !updated.truncated(), "Source synchronization failed during the worktree update; inspect local files");
        requireSyncSource(baseline, revision);
        return inspectLocal(worktree, baseline.route());
    }

    private void requireSyncSource(DevelopmentBaseline baseline, String expectedHead)
    {
        WorktreeSnapshot snapshot = verify(baseline.worktree(), baseline.route());
        checkState(snapshot.head().equals(expectedHead), "Source HEAD changed before synchronization");
        checkState(!snapshot.gitOperationInProgress(), "Source has an open Git operation");
        checkState(output(baseline.worktree(), "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none").isEmpty(),
                "Source has staged, unstaged, or untracked changes");
    }

    public boolean isAncestor(TaskWorktree worktree, String ancestor, String descendant)
    {
        worktrees.inspect(worktree);
        requireCommit(worktree, ancestor);
        requireCommit(worktree, descendant);
        Result result = git.run(worktree.canonicalWorktree(), "merge-base", "--is-ancestor", ancestor, descendant);
        checkState(!result.truncated() && (result.exitCode() == 0 || result.exitCode() == 1), "Cannot inspect development ancestry");
        return result.exitCode() == 0;
    }

    private WorktreeSnapshot verify(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        return verify(worktree, route, worktrees.inspect(worktree));
    }

    private WorktreeSnapshot verify(TaskWorktree worktree, DevelopmentPublicationRoute route, WorktreeSnapshot snapshot)
    {
        checkState(snapshot.worktree().equals(worktree), "Development worktree ownership changed");
        Workspace workspace = workspace(worktree);
        String sourceRemote = workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin";
        checkState(workspace.selectedRepository().equals(route.headRepository()) && workspace.monitoredRepository().equals(route.baseRepository())
                        && workspace.originCloneUrl().equals(route.pushUrl()) && sourceRemote.equals(route.sourceRemote())
                        && assignedPullRequest(worktree).equals(route.assignedPullRequest())
                        && (route.assignedPullRequest().isPresent()
                        || (workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()).equals(route.baseBranch())
                        && worktree.generatedBranch().equals(route.headBranch()))),
                "Development publication routing changed");
        checkState(worktree.physicalRepository().toString().equalsIgnoreCase(route.headRepository().fullName()), "Development head repository changed");
        requireRemote(worktree, "origin", route.pushUrl());
        if (!sourceRemote.equals("origin")) {
            requireRemote(worktree, sourceRemote, workspace.upstreamCloneUrl().orElseThrow());
        }
        checkSha(snapshot.head(), "sha");
        return snapshot;
    }

    private Optional<PullRequestRef> assignedPullRequest(TaskWorktree worktree)
    {
        return assignments.findByTaskId(worktree.taskId().toString()).map(assignment -> {
            checkState(assignment.workspaceId().equals(worktree.workspaceId())
                    && assignment.repo().equalsIgnoreCase(workspace(worktree).monitoredRepository().fullName()), "Assigned pull request workspace changed");
            return PullRequestRef.parse(assignment.repo() + "#" + assignment.number()).orElseThrow();
        });
    }

    private void requireRemote(TaskWorktree worktree, String remote, String expectedUrl)
    {
        // get-url expands insteadOf/pushInsteadOf, which an exact raw config check would miss.
        checkState(output(worktree, "remote", "get-url", "--all", remote).strip().equals(expectedUrl)
                        && output(worktree, "remote", "get-url", "--push", "--all", remote).strip().equals(expectedUrl),
                "Development remote routing changed");
    }

    private Workspace workspace(TaskWorktree worktree)
    {
        return workspaces.findById(worktree.workspaceId()).orElseThrow(() -> new IllegalStateException("Development workspace no longer exists"));
    }

    private String status(TaskWorktree worktree)
    {
        return output(worktree, "status", "--porcelain=v1", "-z", "--untracked-files=all");
    }

    private static StatusCounts countStatus(String status)
    {
        int staged = 0;
        int unstaged = 0;
        int total = 0;
        boolean unmerged = false;
        ImmutableList.Builder<String> untracked = ImmutableList.builder();
        String[] entries = status.split("\u0000", -1);
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index];
            if (entry.isEmpty() && index == entries.length - 1) {
                break;
            }
            checkState(entry.length() > 3 && entry.charAt(2) == ' ', "Git returned invalid development status");
            char x = entry.charAt(0);
            char y = entry.charAt(1);
            total++;
            if (x == '?' && y == '?') {
                untracked.add(entry.substring(3));
            }
            else {
                staged += x == ' ' ? 0 : 1;
                unstaged += y == ' ' ? 0 : 1;
                unmerged |= x == 'U' || y == 'U' || entry.startsWith("AA ") || entry.startsWith("DD ");
                if (x == 'R' || x == 'C' || y == 'R' || y == 'C') {
                    checkState(++index < entries.length - 1 && !entries[index].isEmpty(), "Git returned an incomplete development rename");
                }
            }
        }
        return new StatusCounts(staged, unstaged, total, unmerged, untracked.build());
    }

    private static void hashUntracked(Hasher digest, Path worktree, String name)
    {
        Path path = worktree.resolve(name).normalize();
        checkState(path.startsWith(worktree) && !path.equals(worktree), "Untracked file escaped the development worktree");
        try {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.isSymbolicLink()) {
                add(digest, "symlink");
                add(digest, Files.readSymbolicLink(path).toString());
            }
            else {
                checkState(before.isRegularFile(), "Untracked development entry is not a file");
                add(digest, "file:" + Files.isExecutable(path));
                add(digest, MoreFiles.asByteSource(path, LinkOption.NOFOLLOW_LINKS).hash(Hashing.sha256()).toString());
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            checkState(before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && before.isSymbolicLink() == after.isSymbolicLink(), "Untracked development file changed during inspection");
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot inspect untracked development file", failure);
        }
    }

    private void requireCommit(TaskWorktree worktree, String sha)
    {
        checkSha(sha, "sha");
        Result result = git.run(worktree.canonicalWorktree(), "cat-file", "-e", sha + "^{commit}");
        checkState(result.successful() && !result.truncated(), "Development commit is unavailable locally");
    }

    private String output(TaskWorktree worktree, String... arguments)
    {
        return git.run(
                worktree.canonicalWorktree(),
                arguments).requireOutput(message -> new AgentStorageException("Cannot inspect development Git facts: " + arguments[0] + ": " + message));
    }

    private static void add(Hasher digest, String value)
    {
        digest.putString(value, StandardCharsets.UTF_8).putByte((byte) 0);
    }

    private record StatusCounts(int staged, int unstaged, int total, boolean unmerged, List<String> untrackedPaths)
    {
        private StatusCounts
        {
            untrackedPaths = ImmutableList.copyOf(untrackedPaths);
        }
    }
}
