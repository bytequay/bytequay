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

import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
        WorktreeService service = new WorktreeService(git);

        Optional<WorktreeService.WorktreeHandle> handle =
                service.create(repo, "sess123", "Fix the login redirect loop");
        assertThat(handle).as("worktree create should succeed in a fresh repo").isPresent();
        Path worktreePath = handle.get().worktreePath();
        String branchName = handle.get().branchName();

        // Path mirrors what the design doc promised.
        assertThat(worktreePath.toString())
                .endsWith("/.bytequay/worktrees/dev/sess123-fix-the-login-redirect-loop");
        assertThat(branchName).isEqualTo("dev/sess123-fix-the-login-redirect-loop");
        assertThat(Files.isDirectory(worktreePath)).isTrue();
        // The branch was created and points somewhere; refExists is
        // the cheapest way to assert that without parsing rev-parse.
        assertThat(git.refExists(repo, branchName)).isTrue();

        // .git/info/exclude grew the marker.
        Path excludePath = repo.resolve(".git").resolve("info").resolve("exclude");
        String body = Files.readString(excludePath, StandardCharsets.UTF_8);
        assertThat(body).contains("/.bytequay/");

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
        WorktreeService service = new WorktreeService(git);

        Optional<WorktreeService.WorktreeHandle> first = service.create(repo, "sess1", "first");
        Optional<WorktreeService.WorktreeHandle> second = service.create(repo, "sess2", "second");
        Optional<WorktreeService.WorktreeHandle> third = service.create(repo, "sess3", "third");
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isPresent();

        Path excludePath = repo.resolve(".git").resolve("info").resolve("exclude");
        String body = Files.readString(excludePath, StandardCharsets.UTF_8);
        long markerCount = body.lines()
                .filter(l -> l.trim().equals("/.bytequay/"))
                .count();
        assertThat(markerCount).as("marker line should appear exactly once").isEqualTo(1);
    }

    /** Non-git working dir falls back gracefully — create returns
     *  empty, no exception leaks. */
    @Test
    void testCreateReturnsEmptyForNonGitDirectory(@TempDir Path tempDir)
    {
        GitRunner git = new GitRunner();
        WorktreeService service = new WorktreeService(git);
        Optional<WorktreeService.WorktreeHandle> handle =
                service.create(tempDir, "sess999", "Whatever title");
        assertThat(handle).isEmpty();
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
        WorktreeService service = new WorktreeService(git);

        var h = service.create(repo, "sessX", "a thread").orElseThrow();
        // Wipe the worktree dir without telling git.
        deleteRecursively(h.worktreePath());

        // Must not throw — service swallows + logs.
        service.remove(repo, h.worktreePath().toString(), h.branchName());
    }

    // ── helpers ──────────────────────────────────────────────────

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
