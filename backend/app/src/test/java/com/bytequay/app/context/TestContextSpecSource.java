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
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestContextSpecSource
{
    private static final String PROTOCOL = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void listsThePagesATopicsCodeLinksMostCitedFirstAndLeavesOutWhatNeverIsASpec()
            throws IOException
    {
        command("init", "-q");
        Path plugin = Files.createDirectories(repository.resolve("plugin/delta/src"));
        Files.writeString(plugin.resolve("Checkpoints.java"), """
                // Licensed under http://www.apache.org/licenses/LICENSE-2.0
                // %1$s#action-reconciliation
                // %1$s#add-file-and-remove-file.
                // See https://github.com/trinodb/trino/issues/12011 and https://github.com/delta-io/delta
                // Port of https://github.com/delta-io/delta/blob/f5ebfbb/spark/src/main/scala/Checkpoints.scala#L40
                // Deletion vector ids are Z85 encoded: https://rfc.zeromq.org/spec/32/
                // Discussed in https://delta-users.slack.com/archives/GKTUWT03T/p1585760533005400 and https://issues.apache.org/jira/browse/PARQUET-1246
                """.formatted(PROTOCOL));
        Files.writeString(plugin.resolve("Features.java"), "// " + PROTOCOL + "#table-features\n// https://docs.delta.io/latest/delta-batch.html\n");
        // Build files name XML namespaces and schemas; a home page states no rules. Whether a page is a spec is for the check to read.
        Files.writeString(plugin.getParent().resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>\n");
        Files.writeString(plugin.getParent().resolve("README.md"), "See https://delta.io/ and https://databricks.com/try-databricks\n");
        Files.writeString(repository.resolve("README.md"), PROTOCOL + "#outside-the-topic\n");
        command("add", ".");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        String baseline = command("rev-parse", "HEAD").strip();

        assertThat(ContextSpecSource.scan(git, workspace(), baseline, ImmutableList.of("plugin/delta/"), ImmutableList.of("https://docs.delta.io/latest/")))
                .extracting(ContextSpecSource::url, ContextSpecSource::kind, ContextSpecSource::links, ContextSpecSource::sections, ContextSpecSource::approved)
                .containsExactly(
                        tuple("https://docs.delta.io/latest/", ContextSpecSource.Kind.PAGE, 0, ImmutableList.of(), true),
                        tuple(PROTOCOL, ContextSpecSource.Kind.PAGE, 3, ImmutableList.of("action-reconciliation", "add-file-and-remove-file", "table-features"), false),
                        tuple("https://databricks.com/try-databricks", ContextSpecSource.Kind.PAGE, 1, ImmutableList.of(), false),
                        tuple("https://delta-users.slack.com/archives/GKTUWT03T/p1585760533005400", ContextSpecSource.Kind.PAGE, 1, ImmutableList.of(), false),
                        tuple("https://docs.delta.io/latest/delta-batch.html", ContextSpecSource.Kind.PAGE, 1, ImmutableList.of(), false),
                        tuple("https://issues.apache.org/jira/browse/PARQUET-1246", ContextSpecSource.Kind.PAGE, 1, ImmutableList.of(), false),
                        tuple("https://rfc.zeromq.org/spec/32/", ContextSpecSource.Kind.PAGE, 1, ImmutableList.of(), false),
                        tuple("https://github.com/delta-io/delta/blob/f5ebfbb/spark/src/main/scala/Checkpoints.scala", ContextSpecSource.Kind.REFERENCE_CODE, 1,
                                ImmutableList.of("L40"), false));
        assertThat(ContextSpecSource.scan(git, workspace(), baseline, ImmutableList.of(), ImmutableList.of())).isEmpty();
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
