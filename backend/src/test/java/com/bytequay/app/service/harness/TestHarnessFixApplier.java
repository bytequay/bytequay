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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Edit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestHarnessFixApplier
{
    @TempDir
    Path root;

    private final HarnessFixApplier applier = new HarnessFixApplier();

    @Test
    void validatesEveryAnchorBeforeWritingAnyFile()
            throws Exception
    {
        Path first = root.resolve("first.txt");
        Path second = root.resolve("second.txt");
        Files.writeString(first, "one\n");
        Files.writeString(second, "duplicate duplicate\n");

        assertThatThrownBy(() -> applier.apply(root, diagnosis(List.of(
                new Edit("first.txt", "one", "changed"),
                new Edit("second.txt", "duplicate", "changed")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly once");
        assertThat(Files.readString(first)).isEqualTo("one\n");
        assertThat(Files.readString(second)).isEqualTo("duplicate duplicate\n");
    }

    @Test
    void rejectsSymlinksThatEscapeTheRepository()
            throws Exception
    {
        Path outside = root.resolveSibling("outside-harness.txt");
        Files.writeString(outside, "secret\n");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        }
        catch (UnsupportedOperationException e) {
            assumeTrue(false, "filesystem does not support symlinks");
        }

        assertThatThrownBy(() -> applier.apply(root,
                diagnosis(List.of(new Edit("link.txt", "secret", "changed")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        assertThat(Files.readString(outside)).isEqualTo("secret\n");
    }

    @Test
    void preservesExecutableBits()
            throws Exception
    {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path script = root.resolve("verify.sh");
        Files.writeString(script, "#!/bin/sh\necho old\n");
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, permissions);

        applier.apply(root,
                diagnosis(List.of(new Edit("verify.sh", "old", "new"))));

        assertThat(Files.getPosixFilePermissions(script)).isEqualTo(permissions);
        assertThat(Files.readString(script)).contains("echo new");
    }

    @Test
    void acceptsPureDeterministicRegenerationRecipe()
    {
        Diagnosis recipe = new Diagnosis(
                "generated files are stale", null, "Target", List.of(),
                "failure", "resource", "recipe:regenerate", List.of("regen"),
                0.9, false, "deterministic generator");

        assertThat(applier.applyRecipe(root, recipe))
                .satisfies(fix -> {
                    assertThat(fix.filesChanged()).isEmpty();
                    assertThat(fix.verifyCommands()).containsExactly("regen");
                    assertThat(fix.source()).isEqualTo("recipe:regenerate");
                });
    }

    @Test
    void rejectsRecipeWithoutEditsOrRegeneration()
    {
        Diagnosis recipe = new Diagnosis(
                "cause", null, "Target", List.of(), "failure", "build",
                "recipe:empty", List.of("build"), 0.9, false, "none");

        assertThatThrownBy(() -> applier.applyRecipe(root, recipe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regen");
    }

    private static Diagnosis diagnosis(List<Edit> edits)
    {
        return new Diagnosis("cause", null, "Target", edits, "failure", "build",
                "agent", List.of("build"), 0.9, false, "evidence");
    }
}
