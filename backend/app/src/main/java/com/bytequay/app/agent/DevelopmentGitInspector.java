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
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Read-only Git and GitHub evidence for the live development coordinator. */
public class DevelopmentGitInspector
{
    private final WorktreeService worktrees;
    private final WorkspaceStore workspaces;
    private final GitCommandRunner git;
    private final CredentialService credentials;
    private final GitHubPullRequestReadClient pullRequests;

    public DevelopmentGitInspector(
            WorktreeService worktrees,
            WorkspaceStore workspaces,
            GitCommandRunner git,
            CredentialService credentials,
            GitHubPullRequestReadClient pullRequests)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    /** Captures only local Git and persisted workspace routing; no fetch or GitHub read occurs. */
    public DevelopmentBaseline capture(AgentTask task)
    {
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new IllegalStateException("Development task has no managed worktree"));
        worktrees.inspect(worktree);
        Workspace workspace = workspace(worktree);
        String sourceRemote = workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin";
        String baseBranch = workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch());
        String baseSha = output(worktree, "rev-parse", "--verify", "refs/remotes/" + sourceRemote + "/" + baseBranch + "^{commit}").strip();
        requireSha(baseSha);
        DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(
                workspace.selectedRepository(), workspace.monitoredRepository(), workspace.originCloneUrl(), sourceRemote,
                baseBranch, baseSha, worktree.generatedBranch());
        return new DevelopmentBaseline(worktree, route, inspectLocal(worktree, route));
    }

    public DevelopmentLocalFacts inspectLocal(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        WorktreeSnapshot snapshot = verify(worktree, route);
        requireCommit(worktree, route.baseSha());
        String tree = output(worktree, "rev-parse", "--verify", "HEAD^{tree}").strip();
        requireSha(tree);
        String status = status(worktree);
        StatusCounts counts = countStatus(status);
        MessageDigest digest = digest();
        add(digest, snapshot.head());
        add(digest, tree);
        add(digest, worktree.generatedBranch());
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
        requireSha(mergeBase);
        Result diff = git.run(worktree.canonicalWorktree(), "diff", "--quiet", "--no-ext-diff", "--no-textconv", mergeBase, snapshot.head(), "--");
        checkState(!diff.truncated() && (diff.exitCode() == 0 || diff.exitCode() == 1), "Cannot inspect the committed development diff");
        WorktreeSnapshot after = worktrees.inspect(worktree);
        checkState(after.head().equals(snapshot.head()) && status(worktree).equals(status), "Development files changed during inspection");
        return new DevelopmentLocalFacts(
                worktree.generatedBranch(), snapshot.head(), tree, HexFormat.of().formatHex(digest.digest()),
                counts.staged(), counts.unstaged(), counts.untrackedPaths().size(), counts.total(), commits, diff.exitCode() == 1,
                snapshot.gitOperationInProgress() || after.gitOperationInProgress() || counts.unmerged());
    }

    public DevelopmentPublicationFacts inspectPublication(TaskWorktree worktree, DevelopmentPublicationRoute route)
    {
        verify(worktree, route);
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
            requireSha(values[0]);
            remoteHead = Optional.of(values[0]);
        }
        var matches = pullRequests.fetchPullRequestIdentities(
                credentials.resolveGitHubToken(worktree.workspaceId()), route.baseRepository(), route.headRepository(), route.headBranch());
        verify(worktree, route);
        return new DevelopmentPublicationFacts(remoteHead, matches);
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
        WorktreeSnapshot snapshot = worktrees.inspect(worktree);
        checkState(snapshot.worktree().equals(worktree), "Development worktree ownership changed");
        Workspace workspace = workspace(worktree);
        String sourceRemote = workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin";
        checkState(workspace.selectedRepository().equals(route.headRepository()) && workspace.monitoredRepository().equals(route.baseRepository())
                        && workspace.originCloneUrl().equals(route.pushUrl()) && sourceRemote.equals(route.sourceRemote())
                        && workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()).equals(route.baseBranch())
                        && worktree.generatedBranch().equals(route.headBranch()),
                "Development publication routing changed");
        checkState(worktree.physicalRepository().toString().equalsIgnoreCase(route.headRepository().fullName()), "Development head repository changed");
        requireRemote(worktree, "origin", route.pushUrl());
        if (!sourceRemote.equals("origin")) {
            requireRemote(worktree, sourceRemote, workspace.upstreamCloneUrl().orElseThrow());
        }
        requireSha(snapshot.head());
        return snapshot;
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

    private static void hashUntracked(MessageDigest digest, Path worktree, String name)
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
                MessageDigest content = digest();
                try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes = new byte[8192];
                    int length;
                    while ((length = stream.read(bytes)) != -1) {
                        content.update(bytes, 0, length);
                    }
                }
                add(digest, HexFormat.of().formatHex(content.digest()));
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
        requireSha(sha);
        Result result = git.run(worktree.canonicalWorktree(), "cat-file", "-e", sha + "^{commit}");
        checkState(result.successful() && !result.truncated(), "Development commit is unavailable locally");
    }

    private static void requireSha(String sha)
    {
        requireNonNull(sha, "sha is null");
        checkArgument(sha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), "Development revision is not an exact Git object ID");
    }

    private String output(TaskWorktree worktree, String... arguments)
    {
        Result result = git.run(worktree.canonicalWorktree(), arguments);
        if (!result.successful() || result.truncated()) {
            throw new AgentStorageException("Cannot inspect development Git facts: " + arguments[0]);
        }
        return result.output();
    }

    private static MessageDigest digest()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void add(MessageDigest digest, String value)
    {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record StatusCounts(int staged, int unstaged, int total, boolean unmerged, List<String> untrackedPaths)
    {
        private StatusCounts
        {
            untrackedPaths = ImmutableList.copyOf(untrackedPaths);
        }
    }
}
