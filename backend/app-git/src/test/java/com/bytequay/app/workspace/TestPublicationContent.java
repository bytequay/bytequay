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
package com.bytequay.app.workspace;

import com.bytequay.app.git.command.GitCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPublicationContent
{
    @TempDir
    Path directory;
    private final GitCommandRunner git = new GitCommandRunner();
    private final PublicationContent content = new PublicationContent(git);

    @Test
    void includesDeletedIntermediateSecretsCommitMessagesAndRenamedPaths()
            throws Exception
    {
        String base = initialize();
        Files.writeString(directory.resolve("fixture.txt"), "https://private.example.invalid/customer-repo");
        commit("Private reference CASE-42");
        Files.delete(directory.resolve("fixture.txt"));
        Files.move(directory.resolve("file.txt"), directory.resolve("customer-example.txt"));
        String head = commit("Remove private reference");

        assertThat(content.read(directory, base, head)).contains("private.example.invalid", "CASE-42", "customer-example.txt");
        assertThat(git.run(directory, "diff", base, head).output()).doesNotContain("private.example.invalid");
    }

    @Test
    void allowsUnchangedSubmodulesButRejectsChangedLinksEvenWhenReverted()
            throws Exception
    {
        String initial = initialize();
        run("update-index", "--add", "--cacheinfo", "160000," + initial + ",vendor");
        run("commit", "-m", "Existing public submodule");
        String base = run("rev-parse", "HEAD").strip();
        Files.writeString(directory.resolve("file.txt"), "public example fixed");
        run("add", "file.txt");
        run("commit", "-m", "Fix text");
        String clean = run("rev-parse", "HEAD").strip();
        assertThat(content.read(directory, base, clean)).contains("public example fixed");

        run("config", "diff.ignoreSubmodules", "all");
        run("update-index", "--cacheinfo", "160000," + clean + ",vendor");
        run("commit", "-m", "Change submodule");
        String changed = run("rev-parse", "HEAD").strip();
        assertThatThrownBy(() -> content.read(directory, base, changed)).hasMessageContaining("submodule");
        run("update-index", "--cacheinfo", "160000," + initial + ",vendor");
        run("commit", "-m", "Restore submodule");
        String restored = run("rev-parse", "HEAD").strip();
        assertThatThrownBy(() -> content.read(directory, base, restored)).hasMessageContaining("submodule");
    }

    @Test
    void rejectsNewSubmodules()
            throws Exception
    {
        String base = initialize();
        run("update-index", "--add", "--cacheinfo", "160000," + base + ",vendor");
        run("commit", "-m", "Add submodule");
        String head = run("rev-parse", "HEAD").strip();
        assertThatThrownBy(() -> content.read(directory, base, head)).hasMessageContaining("submodule");
    }

    @Test
    void refusesBinaryAndOversizedCoverage()
            throws Exception
    {
        String base = initialize();
        Files.write(directory.resolve("image.bin"), new byte[] {1, 0, 2});
        String head = commit("Add binary");
        assertThatThrownBy(() -> content.read(directory, base, head)).hasMessageContaining("binary");
        Files.delete(directory.resolve("image.bin"));
        String clean = commit("Remove binary");
        Files.writeString(directory.resolve("large.txt"), "x".repeat(PublicationContent.MAXIMUM_CHARACTERS + 1));
        String large = commit("Add large fixture");
        assertThatThrownBy(() -> content.read(directory, clean, large)).hasMessageContaining("coverage limit");
    }

    private String initialize()
            throws Exception
    {
        run("init");
        run("config", "user.name", "Example Author");
        run("config", "user.email", "author@example.invalid");
        Files.writeString(directory.resolve("file.txt"), "public example");
        return commit("Initial");
    }

    private String commit(String message)
    {
        run("add", "-A");
        run("commit", "-m", message);
        return run("rev-parse", "HEAD").strip();
    }

    private String run(String... args)
    {
        var result = git.run(directory, args);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
