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
package com.bytequay.app.flow.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestImmutableGitObjectReader
{
    @TempDir
    private Path repository;

    private String base;
    private String reviewed;

    @BeforeEach
    void setUp()
            throws IOException
    {
        git("init", "-b", "main");
        git("config", "user.name", "ByteQuay Test");
        git("config", "user.email", "test@bytequay.invalid");
        Files.writeString(repository.resolve("same.txt"), "base\n");
        Files.writeString(repository.resolve("removed.txt"), "removed\n");
        git("add", ".");
        git("commit", "-m", "base");
        base = git("rev-parse", "HEAD");

        Files.writeString(repository.resolve("same.txt"), "reviewed\n");
        Files.delete(repository.resolve("removed.txt"));
        Files.writeString(repository.resolve("added.txt"), "added\n");
        git("add", "-A");
        git("commit", "-m", "reviewed");
        reviewed = git("rev-parse", "HEAD");
    }

    @Test
    void readsOnlyTheExactBaseAndReviewedObjects()
            throws IOException
    {
        ImmutableGitObjectReader reader = reader();

        assertThat(text(reader.readBaseBlob("same.txt")))
                .isEqualTo("base\n");
        assertThat(text(reader.readReviewedBlob("same.txt")))
                .isEqualTo("reviewed\n");
        assertThat(text(reader.readReviewedBlob("added.txt")))
                .isEqualTo("added\n");
        assertThat(reader.listTree()).extracting(
                        ImmutableGitObjectReader.TreeEntry::path)
                .containsExactly("added.txt", "same.txt");
        assertThat(reader.readDiff()).isNotEmpty();

        Files.writeString(repository.resolve("same.txt"), "uncommitted\n");
        assertThat(text(reader.readReviewedBlob("same.txt")))
                .isEqualTo("reviewed\n");
        assertThatThrownBy(() -> reader.readReviewedBlob("removed.txt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.readBaseBlob("added.txt"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ignoresReplaceRefsAndExternalDiffConfiguration()
    {
        git("replace", base, reviewed);
        git("config", "diff.external", "/definitely/not/executable");

        ImmutableGitObjectReader reader = reader();
        assertThat(text(reader.readBaseBlob("same.txt")))
                .isEqualTo("base\n");
        assertThat(reader.readDiff()).isNotEmpty();
    }

    @Test
    void rejectsPartialAndExternalObjectConfiguration()
    {
        assertUnsafeConfig("core.alternateRefsCommand", "printf bad");
        assertUnsafeConfig("extensions.partialClone", "origin");
        assertUnsafeConfig("remote.origin.promisor", "true");
        assertUnsafeConfig("remote.origin.partialCloneFilter", "blob:none");

        git("config", "remote.origin.promisor", "false");
        assertThat(reader().readDiff()).isNotEmpty();
    }

    @Test
    void attributesAndFiltersCannotChangeObjectBytesOrRawDiff()
            throws IOException
    {
        Files.writeString(repository.resolve(".gitattributes"),
                "same.txt filter=hostile diff=hostile\n");
        git("add", ".gitattributes");
        git("commit", "-m", "attributes");
        String attributedHead = git("rev-parse", "HEAD");
        Path marker = repository.resolve("filter-ran");
        String hostile = "/usr/bin/touch " + marker + "; /bin/cat";
        git("config", "filter.hostile.smudge", hostile);
        git("config", "filter.hostile.clean", hostile);
        git("config", "diff.hostile.command", hostile);

        ImmutableGitObjectReader reader = new ImmutableGitObjectReader(
                repository, base, attributedHead);
        assertThat(text(reader.readReviewedBlob("same.txt")))
                .isEqualTo("reviewed\n");
        assertThat(reader.readDiff()).isNotEmpty();
        assertThat(marker).doesNotExist();
    }

    @Test
    void rejectsAlternateAndPromisorObjectStores()
            throws IOException
    {
        Path objects = repository.resolve(".git/objects");
        Files.createDirectories(objects.resolve("info"));
        Files.writeString(objects.resolve("info/alternates"), "/tmp/objects\n");
        assertThatThrownBy(this::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alternate object store");

        Files.delete(objects.resolve("info/alternates"));
        Files.createDirectories(objects.resolve("pack"));
        Files.write(objects.resolve("pack/test.promisor"), new byte[0]);
        assertThatThrownBy(this::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promisor objects");
    }

    @Test
    void rejectsUnsafePathsAndOversizedBlobs()
            throws IOException
    {
        ImmutableGitObjectReader reader = reader();
        assertThatThrownBy(() -> reader.readReviewedBlob("../same.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readReviewedBlob("/same.txt"))
                .isInstanceOf(IllegalArgumentException.class);

        Files.write(repository.resolve("large.bin"),
                new byte[2 * 1024 * 1024 + 1]);
        git("add", "large.bin");
        git("commit", "-m", "large");
        String largeHead = git("rev-parse", "HEAD");
        ImmutableGitObjectReader large = new ImmutableGitObjectReader(
                repository, reviewed, largeHead);
        assertThatThrownBy(() -> large.readReviewedBlob("large.bin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded its bound");
    }

    @Test
    void rejectsInvalidRootsObjectsAndGitlinks()
            throws IOException
    {
        assertThatThrownBy(() -> new ImmutableGitObjectReader(
                repository.resolve("missing"), base, reviewed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImmutableGitObjectReader(
                repository, "HEAD", reviewed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImmutableGitObjectReader(
                repository, "0".repeat(40), reviewed))
                .isInstanceOf(IllegalStateException.class);

        Path other = repository.resolveSibling(
                repository.getFileName() + "-nested-repository");
        Files.createDirectories(other);
        git(other, "init", "-b", "main");
        git(other, "config", "user.name", "ByteQuay Test");
        git(other, "config", "user.email", "test@bytequay.invalid");
        Files.writeString(other.resolve("nested.txt"), "nested\n");
        git(other, "add", ".");
        git(other, "commit", "-m", "nested");
        git("-c", "protocol.file.allow=always", "submodule", "add",
                other.toString(), "nested");
        git("commit", "-m", "gitlink");
        String gitlinkHead = git("rev-parse", "HEAD");
        ImmutableGitObjectReader gitlink = new ImmutableGitObjectReader(
                repository, reviewed, gitlinkHead);
        assertThatThrownBy(gitlink::listTree)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gitlink");
    }

    private void assertUnsafeConfig(String key, String value)
    {
        git("config", key, value);
        assertThatThrownBy(this::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external or partial objects");
        git("config", "--unset", key);
    }

    private ImmutableGitObjectReader reader()
    {
        return new ImmutableGitObjectReader(repository, base, reviewed);
    }

    private static String text(byte[] value)
    {
        return new String(value, StandardCharsets.UTF_8);
    }

    private String git(String... arguments)
    {
        return git(repository, arguments);
    }

    private static String git(Path directory, String... arguments)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output.strip();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
