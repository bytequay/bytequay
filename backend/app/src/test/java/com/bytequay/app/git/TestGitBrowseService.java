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
package com.bytequay.app.git;

import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.git.GitBrowseService.DiffFile;
import com.bytequay.app.git.GitBrowseService.WorktreeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitBrowseService
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private GitBrowseService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        git(created, "config", "user.name", "ByteQuay Test");
        git(created, "config", "user.email", "bytequay@example.com");
        repository = created.toRealPath();
        service = new GitBrowseService(GIT);
    }

    @Test
    void logReportsTitleBodyAuthorAndPullRequestNumber()
    {
        write("a.txt", "one\n");
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", "First change (#412)", "-m", "Longer body\nwith two lines");

        List<Commit> commits = service.log(repository, "main", 10);

        assertThat(commits).hasSize(1);
        Commit commit = commits.getFirst();
        assertThat(commit.title()).isEqualTo("First change (#412)");
        assertThat(commit.body()).isEqualTo("Longer body\nwith two lines");
        assertThat(commit.author()).isEqualTo("ByteQuay Test");
        assertThat(commit.pullRequestNumber()).contains(412);
        assertThat(commit.authoredAt()).isNotBlank();
    }

    @Test
    void diffSplitsPerFileWithStatusesAndCounts()
    {
        write("kept.txt", "line\n");
        write("gone.txt", "bye\n");
        commit("Base");
        String base = head();
        write("kept.txt", "line\nmore\n");
        write("fresh.txt", "hello\n");
        git(repository, "rm", "--quiet", "gone.txt");
        commit("Change");

        List<DiffFile> files = service.diff(repository, base, head());

        assertThat(files).extracting(DiffFile::path)
                .containsExactlyInAnyOrder("kept.txt", "gone.txt", "fresh.txt");
        DiffFile kept = files.stream().filter(file -> file.path().equals("kept.txt")).findFirst().orElseThrow();
        assertThat(kept.status()).isEqualTo("M");
        assertThat(kept.additions()).isEqualTo(1);
        assertThat(kept.patch().orElseThrow()).contains("+more");
        assertThat(files.stream().filter(file -> file.path().equals("fresh.txt")).findFirst().orElseThrow().status())
                .isEqualTo("A");
        assertThat(files.stream().filter(file -> file.path().equals("gone.txt")).findFirst().orElseThrow().status())
                .isEqualTo("D");
    }

    @Test
    void diffHandlesPathsWithSpaces()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();
        write("docs/My Design File.html", "<html>\n");
        commit("Add design");

        List<DiffFile> files = service.diff(repository, base, head());

        assertThat(files).hasSize(1);
        DiffFile file = files.getFirst();
        assertThat(file.path()).isEqualTo("docs/My Design File.html");
        assertThat(file.status()).isEqualTo("A");
        assertThat(file.additions()).isEqualTo(1);
    }

    @Test
    void statusListsTrackedAndUntrackedChanges()
    {
        write("tracked.txt", "one\n");
        commit("Base");
        write("tracked.txt", "one\ntwo\n");
        write("untracked.txt", "new\n");

        WorktreeStatus status = service.status(repository);

        assertThat(status.branch()).isEqualTo("main");
        assertThat(status.files()).extracting(DiffFile::path)
                .containsExactlyInAnyOrder("tracked.txt", "untracked.txt");
        DiffFile untracked = status.files().stream()
                .filter(file -> file.path().equals("untracked.txt"))
                .findFirst()
                .orElseThrow();
        assertThat(untracked.status()).isEqualTo("A");
        assertThat(untracked.additions()).isEqualTo(1);
        assertThat(untracked.patch().orElseThrow()).contains("+new");
    }

    @Test
    void commitRecordsEverything()
    {
        write("a.txt", "one\n");
        String sha = service.commit(repository, "Add a", "With body");

        assertThat(git(repository, "status", "--porcelain")).isBlank();
        assertThat(git(repository, "log", "-1", "--format=%s%n%b")).startsWith("Add a\nWith body");
        assertThat(head()).isEqualTo(sha);
    }

    @Test
    void rewordRewritesAMidHistoryCommitAndKeepsDescendants()
    {
        write("seed.txt", "seed\n");
        commit("Seed");
        write("a.txt", "one\n");
        commit("First");
        String first = head();
        write("b.txt", "two\n");
        commit("Second");

        String rewritten = service.reword(repository, "main", first, "First, but better\n\nNow with a body");

        assertThat(git(repository, "log", "--format=%s")).isEqualTo("Second\nFirst, but better\nSeed\n");
        assertThat(git(repository, "log", "--format=%an", rewritten, "-1")).isEqualTo("ByteQuay Test\n");
        assertThat(git(repository, "status", "--porcelain")).isBlank();
    }

    @Test
    void squashCombinesASpanUnderOneMessage()
    {
        write("a.txt", "one\n");
        commit("Keep below");
        write("b.txt", "two\n");
        commit("Span start");
        String base = head();
        write("c.txt", "three\n");
        commit("Span end");
        String tip = head();
        write("d.txt", "four\n");
        commit("Keep above");

        service.squash(repository, "main", base, tip, "Combined span");

        assertThat(git(repository, "log", "--format=%s"))
                .isEqualTo("Keep above\nCombined span\nKeep below\n");
        assertThat(git(repository, "ls-tree", "--name-only", "HEAD"))
                .contains("a.txt", "b.txt", "c.txt", "d.txt");
    }

    @Test
    void rewritesRefuseADirtyWorktree()
    {
        write("a.txt", "one\n");
        commit("First");
        String first = head();
        write("a.txt", "changed\n");

        assertThatThrownBy(() -> service.reword(repository, "main", first, "New message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncommitted changes");
    }

    private void write(String path, String content)
    {
        try {
            Path target = repository.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commit(String message)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", message);
    }

    private String head()
    {
        return git(repository, "rev-parse", "HEAD").strip();
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.successful())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isTrue();
        return result.output();
    }
}
