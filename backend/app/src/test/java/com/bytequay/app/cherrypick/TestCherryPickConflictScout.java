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
import com.bytequay.app.git.GitCommandRunner;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.bytequay.app.git.GitTestSupport.commit;
import static com.bytequay.app.git.GitTestSupport.commitFile;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
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
        repository = initRepository(created);
        scout = new CherryPickConflictScout(GIT);
    }

    @Test
    void simulatesTheCleanPrefixAndStopsAtTheFirstConflict()
    {
        write(repository, "core/pom.xml", "<project/>\n");
        write(repository, "core/src/Main.java", "one\ntwo\nthree\n");
        commit(repository, "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String clean = commitFile(repository, "core/src/Clean.java", "clean\n", "Clean change");
        String conflicting = commitFile(repository, "core/src/Main.java", "one\nSOURCE\nthree\n", "Conflicting change");
        String later = commitFile(repository, "core/src/After.java", "after\n", "Later change");

        git(repository, "checkout", "--quiet", "main");
        String movedTarget = commitFile(repository, "core/src/Main.java", "one\nTARGET\nthree\n", "Target change");

        Map<String, ConflictPreview> previews = scout.scout(repository, movedTarget, ImmutableList.of(clean, conflicting, later));

        assertThat(previews.get(clean).status()).isEqualTo(Status.CLEAN);

        ConflictPreview conflict = previews.get(conflicting);
        assertThat(conflict.status()).isEqualTo(Status.CONFLICT);
        assertThat(conflict.paths()).containsExactly("core/src/Main.java");

        assertThat(previews.get(later).status()).isEqualTo(Status.NOT_ANALYZED);

        assertNoLeftoverWorktree(movedTarget);
    }

    @Test
    void reportsAddAddAndModifyDeleteConflicts()
    {
        String base = commitFile(repository, "seed.txt", "seed\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String addAdd = commitFile(repository, "both.txt", "source version\n", "Add both.txt on source");

        git(repository, "checkout", "--quiet", "main");
        String target = commitFile(repository, "both.txt", "target version\n", "Add both.txt on target");

        Map<String, ConflictPreview> previews = scout.scout(repository, target, ImmutableList.of(addAdd));
        assertThat(previews.get(addAdd).paths()).containsExactly("both.txt");

        git(repository, "checkout", "--quiet", "-B", "source2", base);
        String withVictim = commitFile(repository, "victim.txt", "content\n", "Add victim");
        String changed = commitFile(repository, "victim.txt", "changed content\n", "Change victim");

        git(repository, "checkout", "--quiet", "-B", "target2", withVictim);
        git(repository, "rm", "--quiet", "victim.txt");
        git(repository, "commit", "--quiet", "-m", "Delete victim on target");
        String deletedTarget = head(repository);

        Map<String, ConflictPreview> deletion = scout.scout(repository, deletedTarget, ImmutableList.of(changed));
        assertThat(deletion.get(changed).paths()).containsExactly("victim.txt");
    }

    @Test
    void keepsGoingPastACommitTheTargetAlreadyContains()
    {
        String base = commitFile(repository, "seed.txt", "seed\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String redundant = commitFile(repository, "shared.txt", "shared\n", "Redundant change");
        String unique = commitFile(repository, "unique.txt", "unique\n", "Unique change");

        git(repository, "checkout", "--quiet", "-B", "target", base);
        String target = commitFile(repository, "shared.txt", "shared\n", "Target already has it");

        Map<String, ConflictPreview> previews = scout.scout(repository, target, ImmutableList.of(redundant, unique));

        assertThat(previews.get(redundant).status()).isEqualTo(Status.CLEAN);
        assertThat(previews.get(unique).status())
                .as("an empty pick must not stop the simulation")
                .isEqualTo(Status.CLEAN);
        assertNoLeftoverWorktree(target);
    }

    @Test
    void removesItsWorktreeEvenWhenTheSimulationCannotStart()
    {
        String target = commitFile(repository, "seed.txt", "seed\n", "Base");

        Map<String, ConflictPreview> previews = scout.scout(repository, target, ImmutableList.of("0".repeat(40)));

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
}
