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
package com.bytequay.app.ci.e2e;

import com.bytequay.app.ci.RoutedGitCommandRunner;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

final class E2eGitRepository
{
    static final String BASE_REF = "main";
    static final String HEAD_REF = "feature";

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, String> DETERMINISTIC_IDENTITY = ImmutableMap.of(
            "GIT_AUTHOR_NAME", "ByteQuay E2E",
            "GIT_AUTHOR_EMAIL", "ci-e2e@bytequay.local",
            "GIT_COMMITTER_NAME", "ByteQuay E2E",
            "GIT_COMMITTER_EMAIL", "ci-e2e@bytequay.local");

    private final PullRequestRef pullRequest;
    private final Path bareRemote;
    private final Path worktree;
    private String baseSha;
    private final String initialHeadSha;

    private E2eGitRepository(
            PullRequestRef pullRequest,
            Path bareRemote,
            Path worktree,
            String baseSha,
            String initialHeadSha)
    {
        this.pullRequest = requireNonNull(pullRequest, "pullRequest is null");
        this.bareRemote = requireNonNull(bareRemote, "bareRemote is null");
        this.worktree = requireNonNull(worktree, "worktree is null");
        this.baseSha = requireNonNull(baseSha, "baseSha is null");
        this.initialHeadSha = requireNonNull(initialHeadSha, "initialHeadSha is null");
    }

    static E2eGitRepository create(Path root, PullRequestRef pullRequest)
    {
        requireNonNull(root, "root is null");
        requireNonNull(pullRequest, "pullRequest is null");
        Path template = requireTemplate(root.getParent());
        Path bareRemote = root.resolve("origin.git");
        Path worktree = root.resolve("repository");
        createDirectory(root);
        String remoteUrl = "https://github.com/" + pullRequest.repoFullName() + ".git";
        git(root,
                "clone", "--bare", "--config", "core.logAllRefUpdates=true",
                template.toString(), bareRemote.toString());
        git(root, "clone", "--branch", HEAD_REF, bareRemote.toString(), worktree.toString());
        git(worktree, "remote", "set-url", "origin", remoteUrl);
        git(
                worktree,
                "config",
                RoutedGitCommandRunner.ROUTE_CONFIG,
                RoutedGitCommandRunner.route(bareRemote, remoteUrl));
        String baseSha = git(worktree, "rev-parse", "origin/" + BASE_REF);
        String headSha = head(worktree);
        try {
            return new E2eGitRepository(
                    pullRequest,
                    bareRemote.toRealPath(),
                    worktree.toRealPath(),
                    baseSha,
                    headSha);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot resolve E2E Git repository", e);
        }
    }

    PullRequestRef pullRequest()
    {
        return pullRequest;
    }

    Path bareRemote()
    {
        return bareRemote;
    }

    Path worktree()
    {
        return worktree;
    }

    String baseSha()
    {
        return baseSha;
    }

    String initialHeadSha()
    {
        return initialHeadSha;
    }

    String localHead()
    {
        return head(worktree);
    }

    String remoteHead()
    {
        String output = git(worktree, "ls-remote", bareRemote.toString(), "refs/heads/" + HEAD_REF);
        return output.split("\\s+", 2)[0];
    }

    String status()
    {
        return git(worktree, "status", "--porcelain=v1", "--untracked-files=all");
    }

    List<String> commitSubjects()
    {
        String output = git(worktree,
                "--git-dir=" + bareRemote,
                "log",
                "--reverse",
                "--format=%s",
                baseSha + "..refs/heads/" + HEAD_REF);
        return output.isEmpty() ? ImmutableList.of() : ImmutableList.copyOf(output.lines().collect(toImmutableList()));
    }

    int headRefUpdates()
    {
        Path reflog = bareRemote.resolve("logs/refs/heads/" + HEAD_REF);
        if (!Files.isRegularFile(reflog)) {
            return 0;
        }
        try (var lines = Files.lines(reflog)) {
            return toIntExact(lines.count());
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot read E2E ref updates", e);
        }
    }

    String mergeBaseWithHead(String ref)
    {
        return git(worktree,
                "--git-dir=" + bareRemote,
                "merge-base",
                ref,
                "refs/heads/" + HEAD_REF);
    }

    String commitOnHead(String relativePath, String content, String subject)
    {
        write(worktree.resolve(relativePath), content);
        git(worktree, "add", relativePath);
        git(worktree, "commit", "-m", subject);
        return localHead();
    }

    String commitOnBase(String relativePath, String content, String subject)
    {
        git(worktree, "checkout", "-B", BASE_REF, baseSha);
        String commit = commitOnHead(relativePath, content, subject);
        git(worktree, "push", bareRemote.toString(), "HEAD:refs/heads/" + BASE_REF);
        baseSha = commit;
        git(worktree, "checkout", HEAD_REF);
        return commit;
    }

    void createPositionedFixup(Path targetWorktree, String relativePath, String content)
    {
        String branch = git(targetWorktree, "branch", "--show-current");
        checkState(!branch.isBlank(), "Task worktree is detached");
        String laterCommit = head(targetWorktree);
        String target = git(targetWorktree, "rev-parse", "HEAD~1");
        write(targetWorktree.resolve(relativePath), content);
        git(targetWorktree, "add", relativePath);
        git(targetWorktree, "commit", "-m", "fixup! Target change");
        String fixup = head(targetWorktree);

        git(targetWorktree, "checkout", "--detach", target);
        git(targetWorktree, "cherry-pick", fixup);
        git(targetWorktree, "cherry-pick", laterCommit);
        moveCurrentBranchToHead(targetWorktree, branch);
    }

    void resolveTargetRebaseConflict(Path targetWorktree)
    {
        write(targetWorktree.resolve("target.txt"), "base target\ntarget\n");
        git(targetWorktree, "add", "target.txt");
    }

    void foldPositionedFixup(Path targetWorktree, String relativePath, String content)
    {
        String branch = git(targetWorktree, "branch", "--show-current");
        checkState(!branch.isBlank(), "Task worktree is detached");
        String laterCommit = head(targetWorktree);
        String fixup = git(targetWorktree, "rev-parse", "HEAD~1");
        git(targetWorktree, "checkout", "--detach", fixup);
        write(targetWorktree.resolve(relativePath), content);
        git(targetWorktree, "add", relativePath);
        git(targetWorktree, "commit", "--amend", "--no-edit");
        git(targetWorktree, "cherry-pick", laterCommit);
        moveCurrentBranchToHead(targetWorktree, branch);
    }

    void createMisplacedFixup(Path targetWorktree, String relativePath, String content)
    {
        write(targetWorktree.resolve(relativePath), content);
        git(targetWorktree, "add", relativePath);
        git(targetWorktree, "commit", "-m", "fixup! Target change");
    }

    void commitInvalidConflictMarkers(Path targetWorktree)
    {
        write(targetWorktree.resolve("target.txt"), "<<<<<<< ours\ntarget\n=======\nother\n>>>>>>> theirs\n");
        git(targetWorktree, "add", "target.txt");
        git(targetWorktree, "commit", "-m", "fixup! Target change");
    }

    void restoreInitialHead(Path targetWorktree)
    {
        git(targetWorktree, "reset", "--hard", initialHeadSha);
    }

    String pushExternalHeadCommit()
    {
        git(worktree, "checkout", "--detach", initialHeadSha);
        String external = commitOnHead("external.txt", "external\n", "External update");
        git(worktree, "push", bareRemote.toString(), "HEAD:refs/heads/" + HEAD_REF);
        git(worktree, "checkout", HEAD_REF);
        return external;
    }

    void checkout(String ref)
    {
        git(worktree, "checkout", ref);
    }

    private static void moveCurrentBranchToHead(Path targetWorktree, String branch)
    {
        git(targetWorktree, "branch", "-f", branch, "HEAD");
        git(targetWorktree, "checkout", branch);
    }

    static String git(Path directory, String... arguments)
    {
        return run(new GitCommandRunner(), directory, arguments);
    }

    static String routedGit(Path directory, String... arguments)
    {
        return run(new RoutedGitCommandRunner(), directory, arguments);
    }

    private static String run(GitCommandRunner runner, Path directory, String... arguments)
    {
        Result result = runner.run(
                directory, COMMAND_TIMEOUT, DETERMINISTIC_IDENTITY, ImmutableList.copyOf(arguments));
        String command = String.join(" ", arguments);
        checkState(result.successful(), "Git failed: %s%n%s", command, result.output());
        checkState(!result.truncated(), "Git output was truncated: %s", command);
        return result.output().strip();
    }

    private static String head(Path worktree)
    {
        return git(worktree, "rev-parse", "HEAD");
    }

    private static synchronized Path requireTemplate(Path suiteDirectory)
    {
        Path templateRoot = suiteDirectory.resolve("_git-template");
        Path bareRemote = templateRoot.resolve("origin.git");
        if (Files.isDirectory(bareRemote)) {
            return bareRemote;
        }
        Path worktree = templateRoot.resolve("worktree");
        createDirectory(templateRoot);
        git(templateRoot, "init", "--bare", bareRemote.toString());
        git(templateRoot, "init", "-b", HEAD_REF, worktree.toString());
        git(worktree, "remote", "add", "origin", bareRemote.toString());

        write(worktree.resolve("source.txt"), "base\n");
        git(worktree, "add", "source.txt");
        git(worktree, "commit", "-m", "Base");
        git(worktree, "push", "origin", "HEAD:refs/heads/" + BASE_REF);

        write(worktree.resolve("first.txt"), "first\n");
        git(worktree, "add", "first.txt");
        git(worktree, "commit", "-m", "First change");
        write(worktree.resolve("target.txt"), "target\n");
        git(worktree, "add", "target.txt");
        git(worktree, "commit", "-m", "Target change");
        write(worktree.resolve("last.txt"), "last\n");
        git(worktree, "add", "last.txt");
        git(worktree, "commit", "-m", "Last change");
        git(worktree, "push", "origin", "HEAD:refs/heads/" + HEAD_REF);
        return bareRemote;
    }

    private static void createDirectory(Path directory)
    {
        try {
            Files.createDirectories(directory);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot create E2E directory " + directory, e);
        }
    }

    private static void write(Path path, String content)
    {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot write E2E fixture " + path, e);
        }
    }
}
