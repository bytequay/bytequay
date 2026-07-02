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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestWorktreeService
{
    /** Slug logic is the bit we own outright — no shelling out, fully
     *  deterministic. Each case pins one edge of the normaliser. */
    @Test
    void testSlugifyDropsPunctuationAndCollapsesRuns()
    {
        assertThat(WorktreeService.slugify("Fix the login redirect loop"))
                .isEqualTo("fix-the-login-redirect-loop");
    }

    @Test
    void testSlugifyHandlesPunctuationRuns()
    {
        assertThat(WorktreeService.slugify("[CI] build failed!!!  twice"))
                .isEqualTo("ci-build-failed-twice");
    }

    @Test
    void testSlugifyDropsApostrophesWithoutSplitting()
    {
        // "Let's" → "lets", not "let-s"; covers both ASCII and curly forms.
        assertThat(WorktreeService.slugify("Let's progress my prs"))
                .isEqualTo("lets-progress-my-prs");
        assertThat(WorktreeService.slugify("Let’s go"))
                .isEqualTo("lets-go");
    }

    @Test
    void testSlugifyTruncatesAtCap()
    {
        String longInput = "a".repeat(100);
        assertThat(WorktreeService.slugify(longInput))
                .hasSize(WorktreeService.SLUG_MAX_CHARS);
    }

    @Test
    void testSlugifyEmptyInputs()
    {
        assertThat(WorktreeService.slugify(null)).isEmpty();
        assertThat(WorktreeService.slugify("")).isEmpty();
        assertThat(WorktreeService.slugify("   ")).isEmpty();
        assertThat(WorktreeService.slugify("!!!@@@###")).isEmpty();
    }

    @Test
    void testSlugifyStripsTrailingDashes()
    {
        // Truncation could leave a dangling "-" mid-word; check it's
        // stripped so the final slug never ends with a dash.
        assertThat(WorktreeService.slugify("Word-".repeat(20)))
                .doesNotEndWith("-");
    }

    /** End-to-end create + remove against a real temp git repo. The
     *  point is to verify (a) the worktree lands at the documented
     *  path, (b) the dev branch is named the documented way, and
     *  (c) {@code .git/info/exclude} grows the marker line. Skipped
     *  with a soft pass if {@code git} isn't on PATH so CI without
     *  git installed (rare but possible) doesn't false-fail. */
    @Test
    void testCreateAndRemoveAgainstRealRepo(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path repo = initEmptyRepo(tempDir);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        Optional<WorktreeService.WorktreeHandle> handle =
                service.create(repo, "sess123", "Fix the login redirect loop");
        assertThat(handle).as("worktree create should succeed in a fresh repo").isPresent();
        Path worktreePath = handle.get().worktreePath();
        String branchName = handle.get().branchName();

        // Path mirrors what the design doc promised: one worktree dir
        // per task, named for the task id.
        assertThat(worktreePath.toString()).endsWith("/.worktrees/sess123");
        // The branch is named for the task's purpose (the title slug),
        // not the task id — short and readable.
        assertThat(branchName).isEqualTo("dev/fix-the-login-redirect-loop");
        assertThat(Files.isDirectory(worktreePath)).isTrue();
        // The branch was created and points somewhere; refExists is
        // the cheapest way to assert that without parsing rev-parse.
        assertThat(git.refExists(repo, branchName)).isTrue();

        // .git/info/exclude grew the marker.
        Path excludePath = repo.resolve(".git").resolve("info").resolve("exclude");
        String body = Files.readString(excludePath, StandardCharsets.UTF_8);
        assertThat(body).contains("/.worktrees/");

        service.remove(repo, worktreePath.toString(), branchName);
        assertThat(Files.exists(worktreePath))
                .as("worktree dir should be gone after remove")
                .isFalse();
        assertThat(git.refExists(repo, branchName))
                .as("dev branch should be deleted after remove")
                .isFalse();
    }

    /** Repeated creates on the same repo must not append the
     *  {@code .git/info/exclude} marker twice — the appender is the
     *  hot path on every thread create, so duplicate lines would
     *  accumulate fast. */
    @Test
    void testExcludeFileAppendIsIdempotent(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path repo = initEmptyRepo(tempDir);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        Optional<WorktreeService.WorktreeHandle> first = service.create(repo, "sess1", "first");
        Optional<WorktreeService.WorktreeHandle> second = service.create(repo, "sess2", "second");
        Optional<WorktreeService.WorktreeHandle> third = service.create(repo, "sess3", "third");
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isPresent();

        Path excludePath = repo.resolve(".git").resolve("info").resolve("exclude");
        String body = Files.readString(excludePath, StandardCharsets.UTF_8);
        long markerCount = body.lines()
                .filter(l -> l.trim().equals("/.worktrees/"))
                .count();
        assertThat(markerCount).as("marker line should appear exactly once").isEqualTo(1);
    }

    /** Non-git working dir falls back gracefully — create returns
     *  empty, no exception leaks. */
    @Test
    void testCreateReturnsEmptyForNonGitDirectory(@TempDir Path tempDir)
    {
        GitRunner git = new GitRunner();
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));
        Optional<WorktreeService.WorktreeHandle> handle =
                service.create(tempDir, "sess999", "Whatever title");
        assertThat(handle).isEmpty();
    }

    /** A worktree commit must NOT carry a {@code BQ-Task:} trailer — the
     *  prepare-commit-msg hook was removed so internal task ids never leak
     *  into commit messages that land on GitHub. Regression guard. */
    @Test
    void testCommitInWorktreeHasNoTaskTrailer(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path repo = initEmptyRepo(tempDir);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        String taskId = "ws-bytequay.t260603-3-a1.k2";
        var handle = service.create(repo, taskId, "No trailer hook").orElseThrow();
        Path worktree = handle.worktreePath();

        // No prepare-commit-msg hook is installed any more.
        Path hookFile = worktree.resolve(".bytequay-hooks").resolve("prepare-commit-msg");
        assertThat(Files.isRegularFile(hookFile)).isFalse();

        runGit(worktree, List.of("git", "config", "user.email", "agent@example.com"));
        runGit(worktree, List.of("git", "config", "user.name", "Agent"));
        Files.writeString(worktree.resolve("hello.txt"), "world", StandardCharsets.UTF_8);
        runGit(worktree, List.of("git", "add", "hello.txt"));
        runGit(worktree, List.of("git", "commit", "-m", "land a commit"));

        String body = readLastCommitBody(worktree);
        assertThat(body)
                .as("commit body must not carry a BQ-Task trailer")
                .doesNotContain("BQ-Task");
    }

    /** Remove is best-effort and tolerates a worktree path that no
     *  longer exists on disk (e.g. user blew it away manually). The
     *  branch delete still runs and surfaces nothing — git's stderr
     *  is logged inside the service. */
    @Test
    void testRemoveToleratesMissingWorktreeDir(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path repo = initEmptyRepo(tempDir);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        var h = service.create(repo, "sessX", "a thread").orElseThrow();
        // Wipe the worktree dir without telling git.
        deleteRecursively(h.worktreePath());

        // Must not throw — service swallows + logs.
        service.remove(repo, h.worktreePath().toString(), h.branchName());
    }

    /** Fork-based clone: the watched repo names an {@code upstream}
     *  remote, so a new task branch must be cut from {@code
     *  upstream/<default>} — not the fork's own {@code main} — after the
     *  upstream is fetched. This is the trino_new-style workflow: branch
     *  off upstream/master, then PR against it. */
    @Test
    void testForkCloneBranchesFromUpstreamDefaultNotForkMain(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        // Upstream repo (stands in for trinodb/trino) with its own
        // distinctive commit on master.
        Path upstream = tempDir.resolve("upstream");
        Files.createDirectories(upstream);
        runGit(upstream, List.of("git", "init", "--initial-branch=master"));
        runGit(upstream, List.of("git", "config", "user.email", "up@example.com"));
        runGit(upstream, List.of("git", "config", "user.name", "Up"));
        Files.writeString(upstream.resolve("UPSTREAM.md"), "u", StandardCharsets.UTF_8);
        runGit(upstream, List.of("git", "add", "UPSTREAM.md"));
        runGit(upstream, List.of("git", "commit", "-m", "upstream base"));

        // Fork clone with a different local main, plus an "upstream"
        // remote pointing at the upstream repo (the locate-existing flow
        // records this remote name on the watched repo).
        Path fork = initEmptyRepo(tempDir);
        runGit(fork, List.of("git", "remote", "add", "upstream", upstream.toString()));
        runGit(fork, List.of("git", "fetch", "upstream"));
        runGit(fork, List.of("git", "remote", "set-head", "upstream", "master"));

        WatchedRepoStore repos = Mockito.mock(WatchedRepoStore.class);
        Mockito.when(repos.findAll()).thenReturn(List.of(new WatchedRepo(
                1L, "trinodb", "trino", 0, fork.toString(),
                /* upstreamRemoteName */ "upstream", /* viewFocus */ "upstream")));
        WorktreeService service = new WorktreeService(git, repos);

        var handle = service.create(fork, "task-fork", "add fork feature").orElseThrow();

        // The new branch starts from upstream/master's tip …
        assertThat(revParse(fork, handle.branchName()))
                .isEqualTo(revParse(fork, "upstream/master"));
        // … and NOT from the fork's own main.
        assertThat(revParse(fork, handle.branchName()))
                .isNotEqualTo(revParse(fork, "main"));
    }

    /** A direct clone (no upstream remote) branches a task from {@code
     *  origin/<default>}, not the local default branch — so un-pushed
     *  commits sitting on the user's local main never leak into a task
     *  branch (and from there into its PR). */
    @Test
    void testDirectCloneBranchesFromOriginDefaultNotAheadLocalMain(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        // The published origin with one commit on main.
        Path origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        runGit(origin, List.of("git", "init", "--initial-branch=main"));
        runGit(origin, List.of("git", "config", "user.email", "o@example.com"));
        runGit(origin, List.of("git", "config", "user.name", "O"));
        Files.writeString(origin.resolve("ORIGIN.md"), "o", StandardCharsets.UTF_8);
        runGit(origin, List.of("git", "add", "ORIGIN.md"));
        runGit(origin, List.of("git", "commit", "-m", "origin base"));

        // A clone whose LOCAL main raced ahead with an un-pushed commit.
        Path clone = initEmptyRepo(tempDir);
        runGit(clone, List.of("git", "remote", "add", "origin", origin.toString()));
        runGit(clone, List.of("git", "fetch", "origin"));
        runGit(clone, List.of("git", "remote", "set-head", "origin", "main"));
        Files.writeString(clone.resolve("LOCAL.md"), "l", StandardCharsets.UTF_8);
        runGit(clone, List.of("git", "add", "LOCAL.md"));
        runGit(clone, List.of("git", "commit", "-m", "un-pushed local commit"));

        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        var handle = service.create(clone, "task-direct", "add a direct feature").orElseThrow();

        // The branch starts from origin/main's published tip …
        assertThat(revParse(clone, handle.branchName()))
                .isEqualTo(revParse(clone, "origin/main"));
        // … and NOT from the ahead, un-pushed local main.
        assertThat(revParse(clone, handle.branchName()))
                .isNotEqualTo(revParse(clone, "main"));
    }

    /** The trunk planning worktree is a detached checkout of the upstream
     *  base, and re-running ensure fetches + resets it to the latest base
     *  — so planning always searches an up-to-date upstream/master, not the
     *  fork's stale main, without touching the user's checkout. */
    @Test
    void testEnsurePlanningWorktreeTracksUpstreamBaseAndRefreshes(@TempDir Path tempDir)
            throws IOException, InterruptedException
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path upstream = tempDir.resolve("upstream");
        Files.createDirectories(upstream);
        runGit(upstream, List.of("git", "init", "--initial-branch=master"));
        runGit(upstream, List.of("git", "config", "user.email", "up@example.com"));
        runGit(upstream, List.of("git", "config", "user.name", "Up"));
        Files.writeString(upstream.resolve("U1.md"), "1", StandardCharsets.UTF_8);
        runGit(upstream, List.of("git", "add", "U1.md"));
        runGit(upstream, List.of("git", "commit", "-m", "upstream c1"));

        Path fork = initEmptyRepo(tempDir);
        runGit(fork, List.of("git", "remote", "add", "upstream", upstream.toString()));
        runGit(fork, List.of("git", "fetch", "upstream"));
        runGit(fork, List.of("git", "remote", "set-head", "upstream", "master"));

        WatchedRepoStore repos = Mockito.mock(WatchedRepoStore.class);
        Mockito.when(repos.findAll()).thenReturn(List.of(new WatchedRepo(
                1L, "trinodb", "trino", 0, fork.toString(), "upstream", "upstream")));
        WorktreeService service = new WorktreeService(git, repos);

        Optional<WorktreeService.PlanningSync> planning = service.ensurePlanningWorktree(fork);
        assertThat(planning).as("planning worktree should be created").isPresent();
        assertThat(planning.get().worktree().toString()).endsWith("/.worktrees/_planning");
        assertThat(planning.get().baseRef()).isEqualTo("upstream/master");
        // Detached at upstream/master's tip — not the fork's main.
        Path planningPath = planning.get().worktree();
        assertThat(revParse(planningPath, "HEAD")).isEqualTo(revParse(fork, "upstream/master"));
        assertThat(revParse(planningPath, "HEAD")).isNotEqualTo(revParse(fork, "main"));

        // Upstream advances; re-ensuring fetches it and fast-forwards the
        // planning worktree to the new tip.
        Files.writeString(upstream.resolve("U2.md"), "2", StandardCharsets.UTF_8);
        runGit(upstream, List.of("git", "add", "U2.md"));
        runGit(upstream, List.of("git", "commit", "-m", "upstream c2"));

        Optional<WorktreeService.PlanningSync> refreshed = service.ensurePlanningWorktree(fork);
        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().worktree()).isEqualTo(planningPath);
        assertThat(revParse(planningPath, "HEAD")).isEqualTo(revParse(fork, "upstream/master"));
    }

    // ── helpers ──────────────────────────────────────────────────

    private static String revParse(Path workingDir, String ref)
            throws IOException, InterruptedException
    {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", ref);
        pb.directory(workingDir.toFile());
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git rev-parse timed out: " + ref);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.exitValue() != 0) {
            throw new IOException("git rev-parse failed (" + p.exitValue() + "): " + ref);
        }
        return out;
    }

    private static Path initEmptyRepo(Path tempDir)
            throws IOException, InterruptedException
    {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, List.of("git", "init", "--initial-branch=main"));
        runGit(repo, List.of("git", "config", "user.email", "test@example.com"));
        runGit(repo, List.of("git", "config", "user.name", "Test"));
        // An initial commit so there's a HEAD to branch from.
        Files.writeString(repo.resolve("README.md"), "x", StandardCharsets.UTF_8);
        runGit(repo, List.of("git", "add", "README.md"));
        runGit(repo, List.of("git", "commit", "-m", "initial"));
        // Mark git unaware of where the binary is — actually no-op,
        // GitRunner uses PATH. The init call above set everything up.
        return repo;
    }

    private static void runGit(Path workingDir, List<String> args)
            throws IOException, InterruptedException
    {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git command timed out: " + args);
        }
        if (p.exitValue() != 0) {
            String stderr = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("git command failed (" + p.exitValue() + "): "
                    + args + " - " + stderr);
        }
    }

    /** Returns the full commit message ({@code %B}) of HEAD — subject,
     *  blank line, body and trailers — so the test can assert on the
     *  trailer block the hook stamped in. */
    private static String readLastCommitBody(Path workingDir)
            throws IOException, InterruptedException
    {
        ProcessBuilder pb = new ProcessBuilder("git", "log", "-1", "--format=%B");
        pb.directory(workingDir.toFile());
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git log timed out");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException("git log failed (" + p.exitValue() + "): " + out);
        }
        return out;
    }

    @Test
    void deleteRemoteBranchPushesADeleteWhenTheBranchReachedTheRemote()
            throws Exception
    {
        GitRunner git = Mockito.mock(GitRunner.class);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        service.deleteRemoteBranch(task(Instant.ofEpochMilli(1_700_000_000_000L)));

        verify(git).deleteRemoteBranch(Path.of("/clone"), "origin", "dev/x");
    }

    @Test
    void deleteRemoteBranchIsANoOpWhenTheBranchNeverReachedTheRemote()
            throws Exception
    {
        GitRunner git = Mockito.mock(GitRunner.class);
        WorktreeService service = new WorktreeService(git, Mockito.mock(WatchedRepoStore.class));

        service.deleteRemoteBranch(task(/* pushedAt */ null));

        verify(git, never()).deleteRemoteBranch(Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** A minimal task with a pushed (or unpushed) {@code dev/x} branch cut
     *  from the {@code /clone} root — enough to exercise the reap guards. */
    private static Task task(Instant pushedAt)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k2", "t1", 2L, TaskStatus.IN_REVIEW, "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, pushedAt, TaskPhase.COMPLETED, null, 0, null);
    }

    private static void deleteRecursively(Path p)
            throws IOException
    {
        if (!Files.exists(p)) {
            return;
        }
        try (var stream = Files.walk(p)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(child -> {
                        try {
                            Files.deleteIfExists(child);
                        }
                        catch (IOException ignored) {
                            // best effort
                        }
                    });
        }
    }
}
