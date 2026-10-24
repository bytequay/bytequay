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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.core.domain.DiffFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;

class TestWorktreeDiffReader
{
    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();
    private final WorktreeDiffReader reader = new WorktreeDiffReader(git);

    @Test
    void snapshotsStagedUnstagedNewAndDeletedFilesWithoutChangingHeadOrIndex()
            throws Exception
    {
        run("init");
        configureUser(repository, "Test", "test@example.invalid");
        Files.writeString(repository.resolve("tracked.txt"), "original\n");
        Files.writeString(repository.resolve("deleted.txt"), "delete me\n");
        Files.writeString(repository.resolve(".gitignore"), "ignored.txt\n");
        run("add", ".");
        run("-c", "commit.gpgsign=false", "commit", "-m", "Initial");
        String head = run("rev-parse", "HEAD").strip();
        Files.writeString(repository.resolve("tracked.txt"), "staged\n");
        run("add", "tracked.txt");
        Files.writeString(repository.resolve("tracked.txt"), "unstaged\n");
        Files.delete(repository.resolve("deleted.txt"));
        Files.writeString(repository.resolve("new.txt"), "new\n");
        Files.writeString(repository.resolve("ignored.txt"), "build output\n");
        String status = reader.require(repository, "status", "--porcelain=v1");
        byte[] index = Files.readAllBytes(repository.resolve(".git/index"));

        String tree = reader.snapshotTree(repository);

        assertThat(run("rev-parse", "HEAD").strip()).isEqualTo(head);
        assertThat(Files.readAllBytes(repository.resolve(".git/index"))).isEqualTo(index);
        assertThat(reader.require(repository, "status", "--porcelain=v1")).isEqualTo(status);
        assertThat(run("show", tree + ":tracked.txt")).isEqualTo("unstaged\n");
        assertThat(run("show", tree + ":new.txt")).isEqualTo("new\n");
        assertThat(reader.diff(repository, head, Optional.of(tree)).files()).extracting(DiffFile::filename)
                .containsExactlyInAnyOrder("tracked.txt", "deleted.txt", "new.txt");
        Files.writeString(repository.resolve("new.txt"), "changed later\n");
        assertThat(reader.snapshotTree(repository)).isNotEqualTo(tree);
        assertThat(run("show", tree + ":new.txt")).isEqualTo("new\n");
    }

    private String run(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
