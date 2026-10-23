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

import com.bytequay.app.git.GitTestSupport;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
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
        initRepository(repository);
        write(repository, "README.md", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n");
        reviewedBase = commit("Start");
        git(repository, "checkout", "--quiet", "-b", "feature");
        write(repository, "src/App.java", "class App {}\n");
        commit("Add the app");
        write(repository, "src/App.java", "class App { int size; }\n");
        reviewedHead = commit("Count the size\n\nCallers need it before they allocate.");
        git(repository, "checkout", "--quiet", "main");
        write(repository, "docs/guide.md", "Guide\n");
        movedBase = commit("Add a guide");
    }

    @Test
    void aRebaseOrACleanMergeOfTheBaseBranchIsTheSameChange()
    {
        assertThat(sameChange("main", reviewedBase, reviewedHead)).isTrue();

        git(repository, "checkout", "--quiet", "-b", "rebased", "feature");
        git(repository, "rebase", "--quiet", "main");
        String rebased = head(repository);
        assertThat(rebased).isNotEqualTo(reviewedHead);
        assertThat(sameChange("main", movedBase, rebased)).isTrue();

        git(repository, "checkout", "--quiet", "-b", "merged", "feature");
        git(repository, "merge", "--quiet", "--no-edit", "main");
        assertThat(sameChange("main", movedBase, head(repository))).isTrue();
    }

    @Test
    void aChangedLineMessageOrBaseBranchIsADifferentChange()
    {
        git(repository, "checkout", "--quiet", "-b", "rebased", "feature");
        git(repository, "rebase", "--quiet", "main");
        String rebased = head(repository);
        assertThat(sameChange("release", movedBase, rebased)).isFalse();

        git(repository, "commit", "--quiet", "--amend", "-m", "Count the size\n\nCallers need it before they allocate memory.");
        assertThat(sameChange("main", movedBase, head(repository))).isFalse();

        git(repository, "checkout", "--quiet", "-B", "edited", rebased);
        write(repository, "src/App.java", "class App { long size; }\n");
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "--amend", "--no-edit");
        assertThat(sameChange("main", movedBase, head(repository))).isFalse();
    }

    @Test
    void whitespaceInsideAStringIsPartOfTheReviewedChange()
    {
        git(repository, "checkout", "--quiet", "feature");
        write(repository, "src/App.java", "class App { String value() { return \"a b\"; } }\n");
        String read = commit("Return a value");
        write(repository, "src/App.java", "class App { String value() { return \"ab\"; } }\n");
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "--amend", "--no-edit");

        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, read), "main", reviewedBase, head(repository))).isFalse();
    }

    @Test
    void indentationIsPartOfTheReviewedChange()
    {
        git(repository, "checkout", "--quiet", "feature");
        write(repository, "result.py", """
                def result(enabled):
                    if enabled:
                        print("enabled")
                        return 1
                    return 2
                """);
        String read = commit("Return the enabled result");
        write(repository, "result.py", """
                def result(enabled):
                    if enabled:
                        print("enabled")
                    return 1
                    return 2
                """);
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "--amend", "--no-edit");

        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, read), "main", reviewedBase, head(repository))).isFalse();
    }

    @Test
    void aBaseThatChangedBesideAHunkOrAHeadThatCannotBeReadIsADifferentChange()
    {
        assertThat(sameChange("main", movedBase, "0".repeat(40))).isFalse();
        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, "0".repeat(40)), "main", reviewedBase, reviewedHead)).isFalse();

        // The reviewed branch edits one line of README.md, and the base then changes a line inside that hunk's context.
        git(repository, "checkout", "--quiet", "-b", "nearby", reviewedBase);
        write(repository, "README.md", "one\ntwo\nthree\nFOUR\nfive\nsix\nseven\neight\n");
        String read = commit("Shout four");
        git(repository, "checkout", "--quiet", "main");
        write(repository, "README.md", "one\ntwo\nthree\nfour\nfive\nSIX\nseven\neight\n");
        String base = commit("Shout six");
        git(repository, "checkout", "--quiet", "nearby");
        git(repository, "rebase", "--quiet", "main");

        assertThat(AgentReviewService.sameChange(GIT, repository, run(reviewedBase, read), "main", base, head(repository))).isFalse();
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
        GitTestSupport.commit(repository, message);
        return head(repository);
    }
}
