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
package com.bytequay.app.review;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitTestSupport;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewScopeFiles
{
    private static final GitCommandRunner GIT = new GitCommandRunner();
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);

    @TempDir
    private Path repository;

    @Test
    void fullCoverageRetainsPublicationFilenames()
            throws IOException
    {
        initRepository(repository);
        Files.createDirectories(repository.resolve("old"));
        Files.writeString(repository.resolve("old/Table.java"), "source\n");
        String base = commit("Original source");
        Files.createDirectories(repository.resolve("新"));
        Files.move(repository.resolve("old/Table.java"), repository.resolve("新/Table.java"));
        String head = commit("Move source");
        ReviewRun run = run(ReviewRun.Scope.FULL, base, head, ImmutableList.of(new DiffFile("新/Table.java", "renamed", 0, 0, null)));

        assertThat(ReviewTools.requiredScopeFiles(GIT, repository, run, Optional.empty())).containsExactly("新/Table.java");
    }

    @Test
    void incrementalScopeIncludesARevertedPathAbsentFromThePublicationDiff()
            throws IOException
    {
        initRepository(repository);
        Files.writeString(repository.resolve("metadata.bin"), "base\u0000bytes");
        String base = commit("Base source");
        Files.writeString(repository.resolve("metadata.bin"), "changed\u0000bytes");
        String priorHead = commit("Update source");
        Files.writeString(repository.resolve("metadata.bin"), "base\u0000bytes");
        String head = commit("Restore source");
        ReviewRun run = run(ReviewRun.Scope.INCREMENTAL, base, head, ImmutableList.of());
        ReviewRun previous = run(ReviewRun.Scope.FULL, base, priorHead, ImmutableList.of())
                .ended(ReviewRun.State.ACCEPTED, Optional.empty(), Instant.EPOCH);

        assertThat(ReviewTools.requiredScopeFiles(GIT, repository, run, Optional.of(previous))).containsExactly("metadata.bin");
    }

    @Test
    void coverageFallbackDoesNotRequireGit()
    {
        GitCommandRunner git = mock(GitCommandRunner.class);
        ReviewRun run = run(ReviewRun.Scope.INCREMENTAL, BASE, HEAD, ImmutableList.of(new DiffFile("current.txt", "modified", 1, 0, "patch")));

        assertThat(ReviewTools.requiredScopeFiles(git, repository, run, Optional.empty())).containsExactly("current.txt");
        verifyNoInteractions(git);
    }

    @Test
    void failedOrTruncatedGitOutputCannotBecomeAnEmptyOrPartialScope()
    {
        GitCommandRunner git = mock(GitCommandRunner.class);
        ReviewRun run = run(ReviewRun.Scope.INCREMENTAL, BASE, HEAD, ImmutableList.of());
        ReviewRun previous = run(ReviewRun.Scope.FULL, BASE, BASE, ImmutableList.of());
        for (GitCommandRunner.Result result : ImmutableList.of(new GitCommandRunner.Result(0, "partial.txt\u0000", true), GitCommandRunner.Result.failed("Missing commit"))) {
            when(git.run(repository, "diff", "--name-only", "--no-renames", "-z", BASE, HEAD, "--")).thenReturn(result);

            assertThatThrownBy(() -> ReviewTools.requiredScopeFiles(git, repository, run, Optional.of(previous))).isInstanceOf(ReviewInvalidRequestException.class);
        }
    }

    private String commit(String message)
    {
        GitTestSupport.commit(repository, message);
        return head(repository);
    }

    private static ReviewRun run(ReviewRun.Scope scope, String base, String head, List<DiffFile> diff)
    {
        return new ReviewRun("run-" + head, "review", 1, scope, "main", base, base, head, Optional.empty(), Optional.empty(), ImmutableList.of(), diff,
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }
}
