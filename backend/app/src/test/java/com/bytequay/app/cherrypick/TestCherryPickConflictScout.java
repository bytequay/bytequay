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
package com.bytequay.app.cherrypick;

import com.bytequay.app.cherrypick.ConflictPreview.Status;
import com.bytequay.app.cherrypick.ConflictPreview.Type;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitTestSupport;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.bytequay.app.git.GitTestSupport.git;
import static org.assertj.core.api.Assertions.assertThat;

class TestCherryPickConflictScout
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private CherryPickConflictScout scout;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        git(created, "config", "user.name", "ByteQuay Test");
        git(created, "config", "user.email", "bytequay@example.com");
        repository = created.toRealPath();
        scout = new CherryPickConflictScout(GIT);
    }

    @Test
    void simulatesTheCleanPrefixAndStopsAtTheFirstConflict()
    {
        write("core/pom.xml", "<project/>\n");
        write("core/src/Main.java", "one\ntwo\nthree\n");
        commit("Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        write("core/src/Clean.java", "clean\n");
        commit("Clean change");
        String clean = head();
        write("core/src/Main.java", "one\nSOURCE\nthree\n");
        commit("Conflicting change");
        String conflicting = head();
        write("core/src/After.java", "after\n");
        commit("Later change");
        String later = head();

        git(repository, "checkout", "--quiet", "main");
        write("core/src/Main.java", "one\nTARGET\nthree\n");
        commit("Target change");
        String movedTarget = head();

        Map<String, ConflictPreview> previews =
                scout.scout(repository, movedTarget, ImmutableList.of(clean, conflicting, later));

        assertThat(previews.get(clean).status()).isEqualTo(Status.CLEAN);
        assertThat(previews.get(clean).basis()).isEqualTo(ConflictPreview.Basis.AFTER_CLEAN_PREFIX);

        ConflictPreview conflict = previews.get(conflicting);
        assertThat(conflict.status()).isEqualTo(Status.CONFLICT);
        assertThat(conflict.files()).hasSize(1);
        assertThat(conflict.files().getFirst().path()).isEqualTo("core/src/Main.java");
        assertThat(conflict.files().getFirst().type()).isEqualTo(Type.CONTENT);
        assertThat(conflict.files().getFirst().module()).contains("core");
        assertThat(conflict.files().getFirst().sections()).isNotEmpty();
        assertThat(conflict.files().getFirst().sections().getFirst().startLine()).isPositive();

        assertThat(previews.get(later).status()).isEqualTo(Status.NOT_ANALYZED);
        assertThat(previews.get(later).basis()).isEqualTo(ConflictPreview.Basis.ESTIMATE);

        assertNoLeftoverWorktree(movedTarget);
    }

    @Test
    void classifiesAddAddAndModifyDeleteFromIndexStages()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        write("both.txt", "source version\n");
        commit("Add both.txt on source");
        String addAdd = head();

        git(repository, "checkout", "--quiet", "main");
        write("both.txt", "target version\n");
        commit("Add both.txt on target");
        String target = head();

        Map<String, ConflictPreview> previews = scout.scout(repository, target, ImmutableList.of(addAdd));
        assertThat(previews.get(addAdd).files().getFirst().type()).isEqualTo(Type.ADD_ADD);

        git(repository, "checkout", "--quiet", "-B", "source2", base);
        write("victim.txt", "content\n");
        commit("Add victim");
        String withVictim = head();
        write("victim.txt", "changed content\n");
        commit("Change victim");
        String changed = head();

        git(repository, "checkout", "--quiet", "-B", "target2", withVictim);
        git(repository, "rm", "--quiet", "victim.txt");
        git(repository, "commit", "--quiet", "-m", "Delete victim on target");
        String deletedTarget = head();

        Map<String, ConflictPreview> deletion = scout.scout(repository, deletedTarget, ImmutableList.of(changed));
        assertThat(deletion.get(changed).files().getFirst().type()).isEqualTo(Type.MODIFY_DELETE);
        assertThat(deletion.get(changed).files().getFirst().sections()).isEmpty();
    }

    @Test
    void treatsBinaryConflictsWithoutLineRanges()
            throws IOException
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        Files.write(repository.resolve("image.bin"), new byte[] {1, 0, 2, 0, 3});
        commit("Add binary on source");
        String source = head();

        git(repository, "checkout", "--quiet", "-B", "target", base);
        Files.write(repository.resolve("image.bin"), new byte[] {9, 0, 8, 0, 7});
        commit("Add binary on target");
        String target = head();

        Map<String, ConflictPreview> previews = scout.scout(repository, target, ImmutableList.of(source));
        assertThat(previews.get(source).files().getFirst().sections())
                .as("a binary conflict has no meaningful line ranges")
                .isEmpty();
    }

    @Test
    void keepsGoingPastACommitTheTargetAlreadyContains()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        write("shared.txt", "shared\n");
        commit("Redundant change");
        String redundant = head();
        write("unique.txt", "unique\n");
        commit("Unique change");
        String unique = head();

        git(repository, "checkout", "--quiet", "-B", "target", base);
        write("shared.txt", "shared\n");
        commit("Target already has it");
        String target = head();

        Map<String, ConflictPreview> previews =
                scout.scout(repository, target, ImmutableList.of(redundant, unique));

        assertThat(previews.get(redundant).status()).isEqualTo(Status.CLEAN);
        assertThat(previews.get(unique).status())
                .as("an empty pick must not stop the simulation")
                .isEqualTo(Status.CLEAN);
        assertNoLeftoverWorktree(target);
    }

    @Test
    void removesItsWorktreeEvenWhenTheSimulationCannotStart()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String target = head();

        Map<String, ConflictPreview> previews =
                scout.scout(repository, target, ImmutableList.of("0".repeat(40)));

        assertThat(previews.values()).allMatch(preview -> preview.status() == Status.NOT_ANALYZED);
        assertNoLeftoverWorktree(target);
    }

    private void assertNoLeftoverWorktree(String expectedHead)
    {
        assertThat(git(repository, "worktree", "list", "--porcelain"))
                .as("preview worktree is removed")
                .doesNotContain("bytequay-preview-");
        assertThat(git(repository, "rev-parse", "HEAD")).isEqualTo(expectedHead);
        assertThat(git(repository, "status", "--porcelain")).isEmpty();
    }

    private void write(String name, String content)
    {
        GitTestSupport.write(repository, name, content);
    }

    private void commit(String subject)
    {
        GitTestSupport.commit(repository, subject);
    }

    private String head()
    {
        return GitTestSupport.head(repository);
    }
}
