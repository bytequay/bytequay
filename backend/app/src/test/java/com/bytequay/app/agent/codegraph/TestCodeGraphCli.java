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
package com.bytequay.app.agent.codegraph;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestCodeGraphCli
{
    @Test
    void profileDeniesEverythingButTheTaskOwnedDirectories()
    {
        Path runtime = Path.of("/r");
        Path workspaces = Path.of("/w");
        String profile = CodeGraphCli.sandboxProfile(
                Path.of("/w/ws/worktrees/task"),
                Optional.of(Path.of("/w/ws/repository/.git")),
                Path.of("/r/task/codegraph"),
                Path.of("/r/task/codegraph-home"),
                ImmutableList.of(runtime, workspaces));

        assertThat(profile).isEqualTo("""
                (version 1)
                (allow default)
                (deny network*)
                (deny file-write*)
                (allow file-write* (subpath "/r/task/codegraph") (subpath "/r/task/codegraph-home") (literal "/dev/null"))
                (deny file-read* (subpath "/r"))
                (deny file-read* (subpath "/w"))
                (allow file-read-metadata (literal "/r/task") (literal "/r") (literal "/w/ws/worktrees") (literal "/w/ws") (literal "/w") (literal "/w/ws/repository"))
                (allow file-read* (subpath "/w/ws/worktrees/task") (subpath "/w/ss/repository/.git") (subpath "/r/task/codegraph") (subpath "/r/task/codegraph-home"))
                """.replace("/w/ss/", "/w/ws/"));
    }

    @Test
    void sandboxConfinesTheHelper(@TempDir Path temp)
            throws IOException
    {
        assumeTrue(Files.isExecutable(CodeGraphCli.SANDBOX_EXEC), "macOS sandbox-exec is required");
        Path runtime = Files.createDirectories(temp.resolve("runtime"));
        Files.createDirectories(runtime.resolve("other-task"));
        Path secret = Files.writeString(runtime.resolve("other-task").resolve("events.jsonl"), "secret");
        Path worktree = Files.createDirectories(temp.resolve("worktree"));
        Path index = runtime.resolve("task").resolve("codegraph");
        Path scratch = runtime.resolve("task").resolve("codegraph-home");
        Path bundle = fakeBundle(temp.resolve("bundle"), """
                #!/bin/sh
                : > "$CODEGRAPH_DATA_DIR/written" && echo "index write allowed" || echo "index write denied"
                : > "$(pwd -P)/worktree-probe" 2>/dev/null && echo "worktree write allowed" || echo "worktree write denied"
                /bin/cat "%s" > /dev/null 2>&1 && echo "other task read allowed" || echo "other task read denied"
                /bin/ls "$(pwd -P)" > /dev/null 2>&1 && echo "source read allowed" || echo "source read denied"
                echo "args: $*"
                """.formatted(secret));
        CodeGraphCli cli = new CodeGraphCli(Optional.of(bundle), new GitCommandRunner(), ImmutableList.of(runtime));

        Result result = cli.run(worktree, index, scratch, Duration.ofSeconds(30), ImmutableList.of("explore", "who calls what"));

        assertThat(result.successful()).as(result.output()).isTrue();
        assertThat(result.output())
                .contains("index write allowed")
                .contains("worktree write denied")
                .contains("other task read denied")
                .contains("source read allowed")
                .contains("args: --liftoff-only --disable-warning=ExperimentalWarning " + bundle.resolve("lib/dist/bin/codegraph.js") + " explore who calls what");
        assertThat(index.resolve("written")).isRegularFile();
        assertThat(worktree.resolve("worktree-probe")).doesNotExist();
    }

    private static Path fakeBundle(Path home, String nodeScript)
            throws IOException
    {
        Files.createDirectories(home.resolve("lib/dist/bin"));
        Files.writeString(home.resolve("BYTEQUAY_BUNDLE"), "fake\n");
        Files.writeString(home.resolve("lib/dist/bin/codegraph.js"), "// fake\n");
        Path node = Files.writeString(home.resolve("node"), nodeScript);
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        return home;
    }
}
