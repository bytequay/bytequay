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

import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

final class E2eGitRepository
{
    static final String BASE_REF = "main";
    static final String HEAD_REF = "feature";

    private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

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
        Path worktree = root.resolve("worktree");
        createDirectory(root);
        String remoteUrl = "https://github.com/" + pullRequest.repoFullName() + ".git";
        String rewrite = "url." + bareRemote.toUri() + ".insteadOf=" + remoteUrl;
        git(root,
                "clone", "--bare", "--config", "core.logAllRefUpdates=true",
                template.toString(), bareRemote.toString());
        git(root,
                "-c", rewrite,
                "clone", "--branch", HEAD_REF, remoteUrl, worktree.toString());
        git(worktree, "config", "url." + bareRemote.toUri() + ".insteadOf", remoteUrl);
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
        String output = git(worktree, "ls-remote", "origin", "refs/heads/" + HEAD_REF);
        return output.split("\\s+", 2)[0];
    }

    String status()
    {
        return git(worktree, "status", "--porcelain=v1", "--untracked-files=all");
    }

    List<String> commitSubjects()
    {
        String output = git(worktree, "log", "--reverse", "--format=%s", baseSha + "..HEAD");
        return output.isEmpty() ? ImmutableList.of() : ImmutableList.copyOf(output.lines().toList());
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
        return git(worktree, "merge-base", ref, "HEAD");
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
        git(worktree, "push", "origin", "HEAD:refs/heads/" + BASE_REF);
        baseSha = commit;
        git(worktree, "checkout", HEAD_REF);
        return commit;
    }

    void createPositionedFixup(String relativePath, String content)
    {
        String laterCommit = localHead();
        String target = git(worktree, "rev-parse", "HEAD~1");
        write(worktree.resolve(relativePath), content);
        git(worktree, "add", relativePath);
        git(worktree, "commit", "-m", "fixup! Target change");
        String fixup = localHead();

        git(worktree, "checkout", "--detach", target);
        git(worktree, "cherry-pick", fixup);
        git(worktree, "cherry-pick", laterCommit);
        moveFeatureToHead();
    }

    void rebaseOntoBaseAndCreatePositionedFixup(String relativePath, String content)
    {
        try {
            git(worktree, "rebase", BASE_REF);
        }
        catch (IllegalStateException conflict) {
            if (!conflict.getMessage().contains("CONFLICT")) {
                throw conflict;
            }
            write(worktree.resolve("target.txt"), "base target\ntarget\n");
            git(worktree, "add", "target.txt");
            git(worktree, "-c", "core.editor=true", "rebase", "--continue");
        }
        createPositionedFixup(relativePath, content);
    }

    void foldPositionedFixup(String relativePath, String content)
    {
        String laterCommit = localHead();
        String fixup = git(worktree, "rev-parse", "HEAD~1");
        git(worktree, "checkout", "--detach", fixup);
        write(worktree.resolve(relativePath), content);
        git(worktree, "add", relativePath);
        git(worktree, "commit", "--amend", "--no-edit");
        git(worktree, "cherry-pick", laterCommit);
        moveFeatureToHead();
    }

    void createMisplacedFixup(String relativePath, String content)
    {
        write(worktree.resolve(relativePath), content);
        git(worktree, "add", relativePath);
        git(worktree, "commit", "-m", "fixup! Target change");
    }

    void commitInvalidConflictMarkers()
    {
        write(worktree.resolve("target.txt"), "<<<<<<< ours\ntarget\n=======\nother\n>>>>>>> theirs\n");
        git(worktree, "add", "target.txt");
        git(worktree, "commit", "-m", "fixup! Target change");
    }

    void restoreInitialHead()
    {
        git(worktree, "reset", "--hard", initialHeadSha);
    }

    String pushExternalHeadCommit()
    {
        git(worktree, "checkout", "--detach", initialHeadSha);
        String external = commitOnHead("external.txt", "external\n", "External update");
        git(worktree, "push", "origin", "HEAD:refs/heads/" + HEAD_REF);
        git(worktree, "checkout", HEAD_REF);
        return external;
    }

    void checkout(String ref)
    {
        git(worktree, "checkout", ref);
    }

    private void moveFeatureToHead()
    {
        git(worktree, "branch", "-f", HEAD_REF, "HEAD");
        git(worktree, "checkout", HEAD_REF);
    }

    static String git(Path directory, String... arguments)
    {
        ImmutableList<String> command = ImmutableList.<String>builder()
                .add("git", "-C", directory.toString())
                .add(arguments)
                .build();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("GIT_AUTHOR_NAME", "ByteQuay E2E");
            builder.environment().put("GIT_AUTHOR_EMAIL", "ci-e2e@bytequay.local");
            builder.environment().put("GIT_COMMITTER_NAME", "ByteQuay E2E");
            builder.environment().put("GIT_COMMITTER_EMAIL", "ci-e2e@bytequay.local");
            process = builder.start();
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot start " + String.join(" ", command), e);
        }

        CompletableFuture<CommandOutput> output = new CompletableFuture<>();
        Thread.ofVirtual().name("ci-e2e-git-output").start(() -> drain(process.getInputStream(), output));
        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git timed out: " + String.join(" ", command));
            }
            CommandOutput result = output.get(1, TimeUnit.SECONDS);
            checkState(process.exitValue() == 0, "Git failed: " + String.join(" ", command) + "\n" + result.text());
            checkState(!result.truncated(), "Git output exceeded " + MAXIMUM_OUTPUT_BYTES + " bytes: " + String.join(" ", command) + "\n" + result.text());
            return result.text().strip();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while waiting for Git", e);
        }
        catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new IllegalStateException("Cannot read Git output", e);
        }
    }

    private static void drain(InputStream stream, CompletableFuture<CommandOutput> output)
    {
        try (stream; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
                int remaining = MAXIMUM_OUTPUT_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            output.complete(new CommandOutput(captured.toString(StandardCharsets.UTF_8), truncated));
        }
        catch (IOException e) {
            output.completeExceptionally(e);
        }
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

    private record CommandOutput(String text, boolean truncated) {}
}
