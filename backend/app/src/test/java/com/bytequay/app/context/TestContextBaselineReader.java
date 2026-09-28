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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextBaselineReader
{
    private static final String SHA = "a".repeat(40);
    private static final String BLOB = "b".repeat(40);
    private static final String PATH = "README.md";
    private static final RepoRef MONITORED = RepoRef.of("trino", "trino");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void readsFrozenBaselineDespiteNewHeadIndexWorkingFilesAndReplacementObjects()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve(PATH), "original\nsource\n");
        String baseline = commit();
        String originalBlob = command("rev-parse", baseline + ":" + PATH).strip();
        Files.writeString(repository.resolve(PATH), "new committed content\n");
        String head = commit();
        command("replace", originalBlob, command("rev-parse", head + ":" + PATH).strip());
        Files.writeString(repository.resolve(PATH), "staged content");
        command("add", PATH);
        Files.writeString(repository.resolve(PATH), "working content");

        ContextBaselineReader.Read read = new ContextBaselineReader(git, CLOCK).read(workspace(), 7, baseline, PATH, 1, Optional.empty());

        assertThat(read.reference()).isEqualTo(new ContextReference.RepositoryFile(7, MONITORED, PATH, baseline));
        assertThat(read.blobId()).isEqualTo(originalBlob);
        assertThat(read.readAt()).isEqualTo(NOW);
        assertThat(read.excerpt()).isEqualTo("1  original\n2  source\n");
        assertThat(read.startLine()).isEqualTo(1);
        assertThat(read.endLine()).isEqualTo(2);
        assertThat(read.totalLines()).isEqualTo(2);
        assertThat(read.truncated()).isFalse();
    }

    @Test
    void discoversFrozenNestedDirectoriesWithoutFollowingSymlinksOrSubmodules()
            throws IOException
    {
        initialize();
        Files.createDirectories(repository.resolve("docs/reference"));
        Files.writeString(repository.resolve(PATH), "Repository overview");
        Files.writeString(repository.resolve("docs/guide.md"), "Guide");
        Files.writeString(repository.resolve("docs/reference/api.md"), "API");
        Files.createSymbolicLink(repository.resolve("documentation"), Path.of("docs"));
        String submodule = commit();
        command("update-index", "--add", "--cacheinfo", "160000," + submodule + ",vendor");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Submodule fixture");
        String baseline = command("rev-parse", "HEAD").strip();
        String docsTree = command("rev-parse", baseline + ":docs").strip();
        Files.delete(repository.resolve("docs/reference/api.md"));
        Files.writeString(repository.resolve("docs/new.md"), "New head documentation");
        String head = commit();
        command("replace", docsTree, command("rev-parse", head + ":docs").strip());
        command("replace", baseline, head);
        Files.writeString(repository.resolve("docs/staged.md"), "Staged documentation");
        command("add", "docs/staged.md");
        Files.writeString(repository.resolve("docs/untracked.md"), "Untracked documentation");
        ContextBaselineReader reader = new ContextBaselineReader(git, CLOCK);

        ContextBaselineReader.Directory root = reader.directory(workspace(), 7, baseline, "", 0);
        assertThat(root.path()).isEmpty();
        assertThat(root.commitSha()).isEqualTo(baseline);
        assertThat(root.page()).isZero();
        assertThat(root.hasMore()).isFalse();
        assertThat(root.entries()).extracting(ContextBaselineReader.TreeEntry::path).containsExactly(PATH, "docs", "documentation", "vendor");
        assertThat(root.entries()).extracting(ContextBaselineReader.TreeEntry::kind).containsExactly("FILE", "DIRECTORY", "SYMLINK", "SUBMODULE");
        assertThat(root.entries().getLast().objectId()).isEqualTo(submodule);
        assertThat(reader.directory(workspace(), 7, baseline, "docs", 0).entries())
                .extracting(ContextBaselineReader.TreeEntry::path).containsExactly("docs/guide.md", "docs/reference");
        assertThat(reader.directory(workspace(), 7, baseline, "docs/reference", 0).entries())
                .extracting(ContextBaselineReader.TreeEntry::path).containsExactly("docs/reference/api.md");
        for (String path : ImmutableList.of(PATH, "documentation", "vendor", "missing")) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, baseline, path, 0)).withMessageContaining("not a directory");
        }
    }

    @Test
    void pagesDirectoryEntriesWithoutOmittingOrRepeatingPaths()
            throws IOException
    {
        initialize();
        Files.createDirectory(repository.resolve("docs"));
        for (int index = 0; index < 205; index++) {
            Files.writeString(repository.resolve("docs/guide-%03d.md".formatted(index)), "Documentation");
        }
        String baseline = commit();
        ContextBaselineReader reader = new ContextBaselineReader(git, CLOCK);

        for (int page = 0; page < 3; page++) {
            ContextBaselineReader.Directory directory = reader.directory(workspace(), 7, baseline, "docs", page);
            assertThat(directory.page()).isEqualTo(page);
            assertThat(directory.hasMore()).isEqualTo(page < 2);
            assertThat(directory.entries()).extracting(ContextBaselineReader.TreeEntry::path)
                    .containsExactlyElementsOf(IntStream.range(page * 100, Math.min(205, (page + 1) * 100))
                            .mapToObj(index -> "docs/guide-%03d.md".formatted(index)).toList());
        }
        assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, baseline, "docs", 3)).withMessageContaining("outside");
    }

    @Test
    void searchesDeepFrozenPathsAndTextWithoutReadingCurrentHead()
            throws IOException
    {
        initialize();
        Files.createDirectories(repository.resolve("unusual/nested/connectors"));
        for (int index = 0; index < 53; index++) {
            Files.writeString(repository.resolve("unusual/nested/connectors/iceberg-%02d.txt".formatted(index)),
                    index == 52 ? "snapshot metadata\ncommit contract\n" : "connector fixture\n");
        }
        String baseline = commit();
        Files.delete(repository.resolve("unusual/nested/connectors/iceberg-52.txt"));
        Files.writeString(repository.resolve("unusual/nested/connectors/delta-new.txt"), "snapshot metadata\n");
        commit();
        ContextBaselineReader reader = new ContextBaselineReader(git, CLOCK);

        ContextBaselineReader.PathSearch first = reader.searchPaths(workspace(), 7, baseline, "iceberg", 0);
        ContextBaselineReader.PathSearch second = reader.searchPaths(workspace(), 7, baseline, "iceberg", 1);
        assertThat(first.matches()).hasSize(50);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.matches()).hasSize(3).allMatch(path -> path.startsWith("unusual/nested/connectors/"));
        assertThat(second.hasMore()).isFalse();
        assertThat(reader.searchPaths(workspace(), 7, baseline, "delta-new", 0).matches()).isEmpty();
        assertThat(reader.searchText(workspace(), 7, baseline, "snapshot metadata", 0).matches())
                .containsExactly(new ContextBaselineReader.TextMatch("unusual/nested/connectors/iceberg-52.txt", 1, "snapshot metadata"));
        assertThat(reader.searchText(workspace(), 7, baseline, "absent", 0).matches()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> reader.searchPaths(workspace(), 7, baseline, "iceberg", 2))
                .withMessageContaining("outside");
    }

    @Test
    void rejectsIncompleteSearchResultsInsteadOfClaimingRepositoryCoverage()
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        when(local.runLocalBoundedStdout(eq(repository), eq(TIMEOUT), eq(5 * 1024 * 1024), anyList()))
                .thenReturn(new GitCommandRunner.Result(0, "deep/file.txt\0", true));
        ContextBaselineReader reader = new ContextBaselineReader(local, CLOCK);

        assertThatIllegalArgumentException().isThrownBy(() -> reader.searchPaths(workspace(), 7, SHA, "file", 0))
                .withMessageContaining("5 MiB");
        assertThatIllegalArgumentException().isThrownBy(() -> reader.searchText(workspace(), 7, SHA, "term", 0))
                .withMessageContaining("5 MiB");
        assertThatIllegalArgumentException().isThrownBy(() -> reader.searchPaths(workspace(), 7, SHA, "\n", 0));
    }

    @Test
    void readsExecutableFilesWithLiteralUnicodeAndPathspecCharacters()
            throws IOException
    {
        initialize();
        String path = "docs/:(glob)*[guide] 空间.md";
        Files.createDirectories(repository.resolve("docs"));
        Files.writeString(repository.resolve(path), "literal source");
        assertThat(repository.resolve(path).toFile().setExecutable(true)).isTrue();
        String baseline = commit();

        assertThat(read(git, run(Optional.of(baseline)), path, 1, Optional.empty()).excerpt()).isEqualTo("1  literal source\n");
    }

    @Test
    void rejectsDirectoriesSymlinksAndMissingPaths()
            throws IOException
    {
        initialize();
        Files.createDirectories(repository.resolve("docs"));
        Files.writeString(repository.resolve("docs/source.md"), "source");
        Files.createSymbolicLink(repository.resolve("link.md"), Path.of("docs/source.md"));
        ReviewRun run = run(Optional.of(commit()));

        for (String path : ImmutableList.of("docs", "link.md", "missing.md")) {
            assertThatIllegalArgumentException().isThrownBy(() -> read(git, run, path, 1, Optional.empty()));
        }
    }

    @Test
    void preservesSuccessfulEmptyReadAndRejectsLinesBeyondIt()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve(PATH), "");
        ReviewRun run = run(Optional.of(commit()));

        ContextBaselineReader.Read empty = read(git, run, PATH, 1, Optional.empty());

        assertThat(empty.totalLines()).isZero();
        assertThat(empty.startLine()).isEqualTo(1);
        assertThat(empty.endLine()).isZero();
        assertThat(empty.excerpt()).isEmpty();
        assertThat(empty.truncated()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> read(git, run, PATH, 2, Optional.empty())).withMessageContaining("outside");
    }

    @Test
    void boundsLineWindowsAndExplicitRangesWithoutOverflow()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve(PATH), IntStream.rangeClosed(1, 300).mapToObj(value -> "line " + value).collect(joining("\n")));
        ReviewRun run = run(Optional.of(commit()));

        ContextBaselineReader.Read read = read(git, run, PATH, 50, Optional.of(Integer.MAX_VALUE));
        assertThat(read.totalLines()).isEqualTo(300);
        assertThat(read.endLine()).isEqualTo(249);
        assertThat(read.excerpt()).startsWith("50  line 50\n").endsWith("249  line 249\n");
        assertThat(read(git, run, PATH, 50, Optional.empty()).endLine()).isEqualTo(249);
        assertThat(read(git, run, PATH, 50, Optional.of(51)).excerpt()).isEqualTo("50  line 50\n51  line 51\n");
        assertThat(read(git, run, PATH, 299, Optional.empty()).endLine()).isEqualTo(300);
        assertThatIllegalArgumentException().isThrownBy(() -> read(git, run, PATH, Integer.MAX_VALUE, Optional.empty())).withMessageContaining("outside");
        assertThatIllegalArgumentException().isThrownBy(() -> read(git, run, PATH, 50, Optional.of(49))).withMessageContaining("precede");
    }

    @Test
    void truncatesOnlyTheNumberedExcerptAtACompleteUtf8Character()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve(PATH), "😀".repeat(6000) + "\nsecond\n");
        ContextBaselineReader.Read read = read(git, run(Optional.of(commit())), PATH, 1, Optional.empty());

        assertThat(read.truncated()).isTrue();
        assertThat(ContextLimits.bytes(read.excerpt())).isEqualTo(16 * 1024 - 1);
        assertThat(read.excerpt()).startsWith("1  😀").endsWith("😀").doesNotContain("…", "\uFFFD");
        assertThat(read.totalLines()).isEqualTo(2);
    }

    @Test
    void readsSmallWindowsInsideLargeSourceFiles()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve("LargeMetadata.java"), "// module source implementation details\n".repeat(10_000));
        String baseline = commit();

        ContextBaselineReader.Read read = new ContextBaselineReader(git, CLOCK)
                .read(workspace(), 7, baseline, "LargeMetadata.java", 5000, Optional.of(5004));

        assertThat(read.totalLines()).isEqualTo(10_000);
        assertThat(read.startLine()).isEqualTo(5000);
        assertThat(read.endLine()).isEqualTo(5004);
        assertThat(read.excerpt().lines()).hasSize(5);
        assertThat(ContextLimits.bytes(read.excerpt())).isLessThan(16 * 1024);
    }

    @Test
    void rejectsOversizedBinaryAndInvalidUtf8Blobs()
            throws IOException
    {
        initialize();
        Files.writeString(repository.resolve("large.md"), "x".repeat(2 * 1024 * 1024 + 1));
        Files.write(repository.resolve("binary"), new byte[] {1, 0, 2});
        Files.write(repository.resolve("invalid"), new byte[] {(byte) 0xC3});
        ReviewRun run = run(Optional.of(commit()));

        for (String path : ImmutableList.of("large.md", "binary", "invalid")) {
            assertThatIllegalArgumentException().isThrownBy(() -> read(git, run, path, 1, Optional.empty()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "100644 blob invalid\tREADME.md\0", "100644 blob %s\tother.md\0", "100644 blob %s\tREADME.md",
            "100644 blob %s\tREADME.md\0extra\0", "040000 tree %s\tREADME.md\0", "120000 blob %s\tREADME.md\0", "160000 commit %s\tREADME.md\0"})
    void rejectsMalformedOrNonRegularInventory(String output)
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        when(local.runLocalBounded(eq(repository), eq(TIMEOUT), eq(8 * 1024), anyList())).thenReturn(new GitCommandRunner.Result(0, output.formatted(BLOB), false));

        assertThatIllegalArgumentException().isThrownBy(() -> read(local, run(Optional.of(SHA)), PATH, 1, Optional.empty()));
    }

    @Test
    void rejectsFailedTimedOutAndTruncatedCommandsWithoutReturningPartialEvidence()
    {
        for (GitCommandRunner.Result failure : ImmutableList.of(GitCommandRunner.Result.failed("Git timed out"),
                new GitCommandRunner.Result(128, "fatal", false), new GitCommandRunner.Result(0, "partial", true))) {
            GitCommandRunner local = mock(GitCommandRunner.class);
            when(local.runLocalBounded(eq(repository), eq(TIMEOUT), anyInt(), anyList())).thenReturn(failure);
            assertThatIllegalArgumentException().isThrownBy(() -> read(local, run(Optional.of(SHA)), PATH, 1, Optional.empty()));

            when(local.runLocalBounded(eq(repository), eq(TIMEOUT), eq(8 * 1024), anyList()))
                    .thenReturn(new GitCommandRunner.Result(0, "100644 blob " + BLOB + "\t" + PATH + "\0", false));
            assertThatIllegalArgumentException().isThrownBy(() -> read(local, run(Optional.of(SHA)), PATH, 1, Optional.empty()))
                    .withMessageContaining("2 MiB");
            verify(local).runLocalBounded(repository, TIMEOUT, 2 * 1024 * 1024, ImmutableList.of("cat-file", "blob", BLOB));
        }
    }

    @Test
    void requiresEnabledContextMatchingRepositoryAndAnAvailableBaseline()
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        ReviewRun absent = runWithoutContext();
        ReviewRun disabled = run(false, 7, Optional.empty());
        ReviewRun different = run(true, 1, Optional.of(SHA));

        for (ReviewRun run : ImmutableList.of(absent, disabled, different, run(Optional.empty()))) {
            assertThatIllegalArgumentException().isThrownBy(() -> read(local, run, PATH, 1, Optional.empty()));
        }
        verifyNoInteractions(local);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/absolute", "../parent", "docs/../parent", "-option", "docs//file", "docs\\file", "docs/./file", "docs/line\nfile"})
    void rejectsInvalidPathsBeforeGit(String path)
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        assertThatIllegalArgumentException().isThrownBy(() -> read(local, run(Optional.of(SHA)), path, 1, Optional.empty()));
        ContextBaselineReader reader = new ContextBaselineReader(local, CLOCK);
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(workspace(), 7, SHA, path, 1, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, SHA, path, 0));
        verifyNoInteractions(local);
    }

    @Test
    void rejectsInvalidFrozenIdentityAndDirectoryPagesBeforeGit()
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        ContextBaselineReader reader = new ContextBaselineReader(local, CLOCK);
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(workspace(), 1, SHA, PATH, 1, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 1, SHA, "", 0));
        for (String baseline : ImmutableList.of("HEAD", "main", "a".repeat(39), SHA + ":docs", "A".repeat(40))) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.read(workspace(), 7, baseline, PATH, 1, Optional.empty()));
            assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, baseline, "", 0));
        }
        for (int page : ImmutableList.of(-1, 10, Integer.MAX_VALUE)) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, SHA, "", page));
        }
        verifyNoInteractions(local);
    }

    @Test
    void rejectsUnavailableTruncatedAndOversizedDirectoryOutput()
    {
        for (GitCommandRunner.Result failure : ImmutableList.of(GitCommandRunner.Result.failed("Git timed out"),
                new GitCommandRunner.Result(0, "040000 tree " + BLOB + "\tdocs\0", true),
                new GitCommandRunner.Result(0, "😀".repeat(32 * 1024 + 1), false))) {
            GitCommandRunner local = mock(GitCommandRunner.class);
            when(local.runLocalBounded(repository, TIMEOUT, 128 * 1024, ImmutableList.of("ls-tree", "-z", SHA))).thenReturn(failure);
            ContextBaselineReader reader = new ContextBaselineReader(local, CLOCK);

            assertThatIllegalArgumentException().isThrownBy(() -> reader.directory(workspace(), 7, SHA, "", 0)).withMessageContaining("128 KiB");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"100644 blob %s\tREADME.md", "100644 blob invalid\tREADME.md\0", "100644 tree %s\tdocs\0", "malformed\0"})
    void rejectsMalformedDirectoryOutput(String output)
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        when(local.runLocalBounded(repository, TIMEOUT, 128 * 1024, ImmutableList.of("ls-tree", "-z", SHA)))
                .thenReturn(new GitCommandRunner.Result(0, output.formatted(BLOB), false));

        assertThatIllegalArgumentException().isThrownBy(() -> new ContextBaselineReader(local, CLOCK).directory(workspace(), 7, SHA, "", 0));
    }

    private ContextBaselineReader.Read read(GitCommandRunner runner, ReviewRun run, String path, int start, Optional<Integer> end)
    {
        return new ContextBaselineReader(runner, CLOCK).read(workspace(), run, path, start, end);
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext("workspace", repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), MONITORED,
                "upstream", "origin", "main", ImmutableList.of());
    }

    private static ReviewRun run(Optional<String> baseline)
    {
        return run(true, 7, baseline);
    }

    private static ReviewRun run(boolean enabled, long repositoryId, Optional<String> baseline)
    {
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("context:run", enabled, 0, repositoryId, baseline,
                enabled ? Optional.of(ContextPacket.create("Prepared context")) : Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                enabled && baseline.isEmpty() ? ImmutableList.of("Baseline unavailable") : ImmutableList.of());
        return runWithoutContext().withContext(snapshot, ImmutableMap.of(), NOW);
    }

    private static ReviewRun runWithoutContext()
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", SHA, SHA, "c".repeat(40), Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(),
                ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private void initialize()
    {
        command("init", "-q");
    }

    private String commit()
    {
        command("add", ".");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        return command("rev-parse", "HEAD").strip();
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
