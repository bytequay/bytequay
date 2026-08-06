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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A commit list must be dated by when a commit LANDED, not by when its
 * author first wrote it — the two differ by however long a contribution
 * waited for review, because the maintainer's rebase rewrites the
 * committer date and preserves the author date. github.com's commit list
 * reads the committer date, so anything else makes an hour-old commit
 * read as days old.
 */
class TestGitRunnerCommitDates
{
    private final GitRunner git = new GitRunner();

    @Test
    void carriesBothTheAuthorDateAndTheDateTheCommitLanded(@TempDir Path repo)
            throws Exception
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "base", null);
        // Written a week ago, landed now — what a rebased contribution
        // looks like on the branch it was merged into.
        commit(repo, "feature.txt", "contributed a week ago",
                "2026-07-30T09:00:00+00:00");

        GitRunner.CommitEntry head = git.listCommits(repo, "HEAD", 1).get(0);

        assertThat(head.subject()).isEqualTo("contributed a week ago");
        assertThat(head.authoredAt()).startsWith("2026-07-30T09:00:00");
        assertThat(head.committedAt()).isNotEqualTo(head.authoredAt());
    }

    private static void commit(Path repo, String file, String message, String authorDate)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), message);
        git(repo, "add", ".");
        if (authorDate == null) {
            git(repo, "commit", "-m", message);
        }
        else {
            git(repo, Map.of("GIT_AUTHOR_DATE", authorDate),
                    "commit", "-m", message);
        }
    }

    private static void git(Path repo, String... args)
            throws IOException, InterruptedException
    {
        git(repo, Map.of(), args);
    }

    private static void git(Path repo, Map<String, String> env, String... args)
            throws IOException, InterruptedException
    {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed in " + repo + ": " + output);
        }
    }
}
