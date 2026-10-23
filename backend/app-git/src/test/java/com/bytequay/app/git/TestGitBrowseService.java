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
import com.bytequay.app.git.GitBrowseService.FileContent;
import com.bytequay.app.git.GitBrowseService.WorktreeStatus;
import com.bytequay.app.git.command.GitCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.bytequay.app.git.GitTestSupport.commit;
import static com.bytequay.app.git.GitTestSupport.commitFile;
import static com.bytequay.app.git.GitTestSupport.gitOutput;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
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
        repository = initRepository(temporaryDirectory.resolve("repo"));
        service = new GitBrowseService(GIT);
    }

    @Test
    void logReportsTitleBodyAuthorPullRequestNumberAndDistinctDates()
    {
        write(repository, "a.txt", "one\n");
        gitOutput(repository, "add", "-A");
        gitOutput(repository, "commit", "--quiet", "--date=2001-02-03T04:05:06Z", "-m", "First change (#412)", "-m", "Longer body\nwith two lines");

        List<Commit> commits = service.log(repository, "main", 10);

        assertThat(commits).hasSize(1);
        Commit commit = commits.getFirst();
        assertThat(commit.title()).isEqualTo("First change (#412)");
        assertThat(commit.body()).isEqualTo("Longer body\nwith two lines");
        assertThat(commit.author()).isEqualTo("ByteQuay Test");
        assertThat(commit.committer()).isEqualTo("ByteQuay Test");
        assertThat(commit.pullRequestNumber()).contains(412);
        assertThat(commit.authoredAt())
                .isEqualTo(gitOutput(repository, "log", "-1", "--format=%aI").strip());
        assertThat(commit.committedAt())
                .isEqualTo(gitOutput(repository, "log", "-1", "--format=%cI").strip())
                .isNotEqualTo(commit.authoredAt());
    }

    @Test
    void diffSplitsPerFileWithStatusesAndCounts()
    {
        write(repository, "kept.txt", "line\n");
        String base = commitFile(repository, "gone.txt", "bye\n", "Base");
        write(repository, "kept.txt", "line\nmore\n");
        write(repository, "fresh.txt", "hello\n");
        gitOutput(repository, "rm", "--quiet", "gone.txt");
        commit(repository, "Change");

        List<DiffFile> files = service.diff(repository, base, head(repository));

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
        String base = commitFile(repository, "seed.txt", "seed\n", "Base");
        write(repository, "docs/My Design File.html", "<html>\n");
        commit(repository, "Add design");

        List<DiffFile> files = service.diff(repository, base, head(repository));

        assertThat(files).hasSize(1);
        DiffFile file = files.getFirst();
        assertThat(file.path()).isEqualTo("docs/My Design File.html");
        assertThat(file.status()).isEqualTo("A");
        assertThat(file.additions()).isEqualTo(1);
    }

    @Test
    void diffFileContentReturnsBothRevisions()
    {
        String base = commitFile(repository, "folder/file.txt", "before\n", "Base");
        write(repository, "folder/file.txt", "after\n");
        commit(repository, "Change");

        FileContent content = service.diffFileContent(repository, base, head(repository), "folder/file.txt");

        assertThat(content.oldContent()).contains("before\n");
        assertThat(content.newContent()).contains("after\n");
    }

    @Test
    void statusListsTrackedAndUntrackedChanges()
    {
        write(repository, "tracked.txt", "one\n");
        commit(repository, "Base");
        write(repository, "tracked.txt", "one\ntwo\n");
        write(repository, "untracked.txt", "new\n");

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
        write(repository, "a.txt", "one\n");
        String sha = service.commit(repository, "Add a", "With body");

        assertThat(gitOutput(repository, "status", "--porcelain")).isBlank();
        assertThat(gitOutput(repository, "log", "-1", "--format=%s%n%b")).startsWith("Add a\nWith body");
        assertThat(head(repository)).isEqualTo(sha);
    }

    @Test
    void rewordRewritesAMidHistoryCommitAndKeepsDescendants()
    {
        write(repository, "seed.txt", "seed\n");
        commit(repository, "Seed");
        String first = commitFile(repository, "a.txt", "one\n", "First");
        write(repository, "b.txt", "two\n");
        commit(repository, "Second");

        String rewritten = service.reword(repository, "main", first, "First, but better\n\nNow with a body");

        assertThat(gitOutput(repository, "log", "--format=%s")).isEqualTo("Second\nFirst, but better\nSeed\n");
        assertThat(gitOutput(repository, "log", "--format=%an", rewritten, "-1")).isEqualTo("ByteQuay Test\n");
        assertThat(gitOutput(repository, "status", "--porcelain")).isBlank();
    }

    @Test
    void squashCombinesASpanUnderOneMessage()
    {
        write(repository, "a.txt", "one\n");
        commit(repository, "Keep below");
        String base = commitFile(repository, "b.txt", "two\n", "Span start");
        String tip = commitFile(repository, "c.txt", "three\n", "Span end");
        write(repository, "d.txt", "four\n");
        commit(repository, "Keep above");

        service.squash(repository, "main", base, tip, "Combined span");

        assertThat(gitOutput(repository, "log", "--format=%s"))
                .isEqualTo("Keep above\nCombined span\nKeep below\n");
        assertThat(gitOutput(repository, "ls-tree", "--name-only", "HEAD"))
                .contains("a.txt", "b.txt", "c.txt", "d.txt");
    }

    @Test
    void rewritesRefuseADirtyWorktree()
    {
        String first = commitFile(repository, "a.txt", "one\n", "First");
        write(repository, "a.txt", "changed\n");

        assertThatThrownBy(() -> service.reword(repository, "main", first, "New message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncommitted changes");
    }
}
