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
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewHeadCheck
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path repository;
    private String reviewedBase;
    private String reviewedHead;
    private String movedBase;

    /** A two-commit feature branch that was reviewed, and a base branch that has since moved in another file. */
    @BeforeEach
    void repository()
            throws IOException
    {
        git("init", "--quiet", "-b", "main");
        git("config", "user.name", "ByteQuay Test");
        git("config", "user.email", "bytequay@example.com");
        write("README.md", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n");
        reviewedBase = commit("Start");
        git("checkout", "--quiet", "-b", "feature");
        write("src/App.java", "class App {}\n");
        commit("Add the app");
        write("src/App.java", "class App { int size; }\n");
        reviewedHead = commit("Count the size\n\nCallers need it before they allocate.");
        git("checkout", "--quiet", "main");
        write("docs/guide.md", "Guide\n");
        movedBase = commit("Add a guide");
    }

    @Test
    void aRebaseOrACleanMergeOfTheBaseBranchIsTheSameChange()
    {
        assertThat(sameChange("main", reviewedBase, reviewedHead)).isTrue();

        git("checkout", "--quiet", "-b", "rebased", "feature");
        git("rebase", "--quiet", "main");
        String rebased = git("rev-parse", "HEAD").strip();
        assertThat(rebased).isNotEqualTo(reviewedHead);
        assertThat(sameChange("main", movedBase, rebased)).isTrue();

        git("checkout", "--quiet", "-b", "merged", "feature");
        git("merge", "--quiet", "--no-edit", "main");
        assertThat(sameChange("main", movedBase, git("rev-parse", "HEAD").strip())).isTrue();
    }

    @Test
    void aChangedLineMessageOrBaseBranchIsADifferentChange()
            throws IOException
    {
        git("checkout", "--quiet", "-b", "rebased", "feature");
        git("rebase", "--quiet", "main");
        String rebased = git("rev-parse", "HEAD").strip();
        assertThat(sameChange("release", movedBase, rebased)).isFalse();

        git("commit", "--quiet", "--amend", "-m", "Count the size\n\nCallers need it before they allocate memory.");
        assertThat(sameChange("main", movedBase, git("rev-parse", "HEAD").strip())).isFalse();

        git("checkout", "--quiet", "-B", "edited", rebased);
        write("src/App.java", "class App { long size; }\n");
        git("add", "-A");
        git("commit", "--quiet", "--amend", "--no-edit");
        assertThat(sameChange("main", movedBase, git("rev-parse", "HEAD").strip())).isFalse();
    }

    @Test
    void aBaseThatChangedBesideAHunkOrAHeadThatCannotBeReadIsADifferentChange()
            throws IOException
    {
        assertThat(sameChange("main", movedBase, "0".repeat(40))).isFalse();
        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, "0".repeat(40)), "main", reviewedBase, reviewedHead)).isFalse();

        // The reviewed branch edits one line of README.md, and the base then changes a line inside that hunk's context.
        git("checkout", "--quiet", "-b", "nearby", reviewedBase);
        write("README.md", "one\ntwo\nthree\nFOUR\nfive\nsix\nseven\neight\n");
        String read = commit("Shout four");
        git("checkout", "--quiet", "main");
        write("README.md", "one\ntwo\nthree\nfour\nfive\nSIX\nseven\neight\n");
        String base = commit("Shout six");
        git("checkout", "--quiet", "nearby");
        git("rebase", "--quiet", "main");

        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, read), "main", base, git("rev-parse", "HEAD").strip())).isFalse();
    }

    private boolean sameChange(String baseRef, String mergeBaseSha, String headSha)
    {
        return AgentReviewService.sameChange(GIT, repository, run(reviewedBase, reviewedHead), baseRef, mergeBaseSha, headSha);
    }

    private static ReviewRun run(String mergeBaseSha, String headSha)
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", mergeBaseSha, mergeBaseSha, headSha, Optional.empty(),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }

    private String commit(String message)
    {
        git("add", "-A");
        git("commit", "--quiet", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    private void write(String path, String content)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String git(String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(repository, arguments);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
