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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestContextSpecModule
{
    private static final String PROTOCOL = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void listsTheModulesWhoseOwnCodeCitesAPageInTheMostSectionsFirst()
            throws IOException
    {
        command("init", "-q");
        write("pom.xml", "<project/>\n");
        write("plugin/delta/pom.xml", "<project/>\n");
        write("plugin/delta/src/main/java/Checkpoints.java", """
                // %1$s#action-reconciliation
                // %1$s#add-file-and-remove-file and %1$s#deletion-vectors
                // Licensed under http://www.apache.org/licenses/LICENSE-2.0
                """.formatted(PROTOCOL));
        // Tests, docs and bundled third-party code say nothing about what a module implements.
        write("plugin/delta/src/test/java/TestCheckpoints.java", "// https://docs.delta.io/latest/a.html#one https://docs.delta.io/latest/a.html#two "
                + "https://docs.delta.io/latest/a.html#three https://docs.delta.io/latest/a.html#four\n");
        write("plugin/hive/pom.xml", "<project/>\n");
        write("plugin/hive/src/main/java/Partitions.java", "// https://docs.aws.amazon.com/glue/latest/dg/partitions.html#keys\n");
        write("plugin/empty/pom.xml", "<project/>\n");
        write("web/package.json", "{}\n");
        write("web/vendor/vis.js", "// https://tc39.es/ecma262/#sec-a https://tc39.es/ecma262/#sec-b https://tc39.es/ecma262/#sec-c https://tc39.es/ecma262/#sec-d\n");
        write("docs/pom.xml", "<project/>\n");
        write("docs/src/main/sphinx/delta.md", PROTOCOL + "#checkpoints\n");
        command("add", ".");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        ContextBuildService.Baseline baseline = new ContextBuildService.Baseline("main", command("rev-parse", "HEAD").strip());

        assertThat(ContextSpecModule.scan(git, workspace(), baseline))
                .extracting(ContextSpecModule::directory, ContextSpecModule::page, ContextSpecModule::sections, ContextSpecModule::links)
                .containsExactly(
                        tuple("plugin/delta/", Optional.of(PROTOCOL), 3, 3),
                        tuple("plugin/hive/", Optional.of("https://docs.aws.amazon.com/glue/latest/dg/partitions.html"), 1, 1),
                        tuple("plugin/empty/", Optional.empty(), 0, 0),
                        tuple("web/", Optional.empty(), 0, 0));
    }

    private void write(String path, String text)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext("workspace", repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), RepoRef.of("trinodb", "trino"),
                "upstream", "origin", "main", ImmutableList.of());
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("%s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
