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
package com.bytequay.app.service.local;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Thin wrapper around the system {@code git} binary. We shell out
 * (rather than embedding JGit) so the user's existing gitconfig, SSH
 * keys, signing keys, and credential helper apply to ByteQuay
 * automatically — see project_jgit_fallback.md for the rationale.
 *
 * Operations are synchronous and timeout-bounded; the caller is
 * responsible for off-thread invocation when the latency would block
 * the request thread.
 */
@Component
public class GitRunner
{
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Returns true iff `git --version` succeeded — used as a startup
     * probe so the rest of the local-repo stack can short-circuit
     * with a friendly "install git" message when the binary is
     * missing.
     */
    public boolean isAvailable()
    {
        try {
            GitResult result = run(List.of("git", "--version"), null, 5);
            return result.exitCode() == 0;
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs {@code git status --porcelain} in {@code workingDir} and
     * returns the count of reported lines. Each line in the porcelain
     * output represents one changed file (modified, added, untracked,
     * or staged), so the count is the dirty-file pill the UI renders.
     */
    public int countDirtyFiles(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "status", "--porcelain"), workingDir);
        result.requireSuccess();
        if (result.stdout().isEmpty()) {
            return 0;
        }
        // --porcelain emits one line per entry, lines have no trailing
        // newline mid-buffer but the final line does. Split on \n and
        // drop empties so an empty buffer doesn't read as one entry.
        return (int) result.stdout().lines().filter(s -> !s.isEmpty()).count();
    }

    /**
     * Returns the current branch name, or null if HEAD is detached
     * (e.g. the user checked out a tag or specific commit). Powers
     * the branch chip on the repo detail header.
     */
    public String currentBranch(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), workingDir);
        result.requireSuccess();
        String branch = result.stdout().strip();
        // git emits the literal "HEAD" when the working tree is in
        // detached-HEAD mode; treat that as "no branch".
        return branch.isEmpty() || "HEAD".equals(branch) ? null : branch;
    }

    /**
     * Returns the configured `origin` remote URL of the working tree
     * at {@code path}, or null if no origin is set. Used by the
     * Locate-existing flow to verify the user picked a folder whose
     * remote matches the watched repo before we record it.
     */
    public String originUrl(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "config", "--get", "remote.origin.url"), workingDir, 5);
        // Exit code 1 when the key isn't set — treat that as "no
        // origin", which lets the locate flow fail with a clean
        // mismatch message instead of a stack trace.
        if (result.exitCode() != 0) {
            return null;
        }
        String url = result.stdout().strip();
        return url.isEmpty() ? null : url;
    }

    /**
     * Runs {@code git clone} and waits for completion. Big repos
     * (trino, kubernetes, …) routinely take several minutes; the
     * timeout is bumped accordingly. Real progress streaming via
     * git's --progress and a long-running IPC channel is a follow-up
     * — for now the caller blocks on this, and the UI shows a
     * "Cloning…" state.
     */
    public void clone(String url, Path destination)
            throws IOException, InterruptedException
    {
        requireNonNull(url, "url is null");
        requireNonNull(destination, "destination is null");
        Files.createDirectories(destination.getParent());
        // 30-minute cap. A clone that doesn't finish in 30 minutes is
        // almost certainly stuck on auth or DNS — better to fail and
        // surface the stderr than to hang the IPC indefinitely.
        GitResult result = run(
                List.of("git", "clone", url, destination.toString()),
                null,
                1800);
        result.requireSuccess();
    }

    /**
     * True iff {@code path} is the root of a git working tree (or
     * inside one). Used to validate the user-picked folder in the
     * "Locate existing" flow before we record it on the watched repo.
     */
    public boolean isGitWorkingTree(Path path)
    {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        try {
            GitResult result = run(List.of("git", "rev-parse", "--is-inside-work-tree"), path, 5);
            return result.exitCode() == 0 && "true".equals(result.stdout().strip());
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private GitResult run(List<String> args, Path workingDir)
            throws IOException, InterruptedException
    {
        return run(args, workingDir, DEFAULT_TIMEOUT_SECONDS);
    }

    private GitResult run(List<String> args, Path workingDir, long timeoutSeconds)
            throws IOException, InterruptedException
    {
        requireNonNull(args, "args is null");
        ProcessBuilder pb = new ProcessBuilder(args);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        // Force English output regardless of the user's LANG so we can
        // pattern-match error strings reliably.
        pb.environment().put("LC_ALL", "C");
        // Refuse interactive credential prompts — if `git push` over
        // HTTPS would block on a username/password tty, we want it to
        // fail fast instead of hanging the request thread.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = pb.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + args + " timed out after " + timeoutSeconds + "s");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitResult(process.exitValue(), stdout, stderr, ImmutableList.copyOf(args));
    }

    public record GitResult(int exitCode, String stdout, String stderr, List<String> args)
    {
        public void requireSuccess()
        {
            if (exitCode != 0) {
                throw new GitCommandException(args, exitCode, stderr);
            }
        }
    }

    public static class GitCommandException
            extends RuntimeException
    {
        private final int exitCode;
        private final String stderr;

        GitCommandException(List<String> args, int exitCode, String stderr)
        {
            super("git " + args + " exited " + exitCode + ": " + stderr.strip());
            this.exitCode = exitCode;
            this.stderr = stderr;
        }

        public int exitCode() { return exitCode; }
        public String stderr() { return stderr; }
    }
}
