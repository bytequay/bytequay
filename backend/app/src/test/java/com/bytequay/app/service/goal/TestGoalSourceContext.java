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
package com.bytequay.app.service.goal;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestGoalSourceContext
{
    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final GoalSourceContext context = new GoalSourceContext(repositories, git);

    @Test
    void readsOnlyTheMonitoredBaselineWithoutChangingTheCheckout()
            throws IOException
    {
        command("init", "-q");
        Files.writeString(repository.resolve("Manager.java"), "class ResourceManager { /* fetched baseline */ }\n");
        command("add", ".");
        commit();
        String baseline = command("rev-parse", "HEAD").strip();
        command("update-ref", "refs/remotes/upstream/main", baseline);
        Files.writeString(repository.resolve("Manager.java"), "class ResourceManager { /* local commit */ }\n");
        command("add", ".");
        commit();
        command("update-ref", "refs/remotes/origin/main", "HEAD");
        String head = command("rev-parse", "HEAD");
        Files.writeString(repository.resolve("Manager.java"), "class ResourceManager { /* dirty checkout */ }\n");
        Files.writeString(repository.resolve("Untracked.java"), "class ResourceManager { /* untracked */ }\n");
        String status = command("status", "--porcelain=v1");
        doReturn(workspace()).when(repositories).requireLocalRepository("workspace");

        assertThat(context.find("workspace", "ResourceManager")).hasValueSatisfying(found -> {
            assertThat(found.commit()).isEqualTo(baseline);
            assertThat(found.text()).contains(baseline + ":Manager.java:1:", "fetched baseline").doesNotContain("local commit", "dirty checkout", "untracked");
            assertThat(found.truncated()).isFalse();
        });
        assertThat(command("rev-parse", "HEAD")).isEqualTo(head);
        assertThat(command("status", "--porcelain=v1")).isEqualTo(status);
        verify(repositories).requireLocalRepository("workspace");
        verifyNoMoreInteractions(repositories);
    }

    @Test
    void boundsEvidenceAndOmitsMissingOrUnavailableResults()
            throws IOException
    {
        assertThat(context.find("workspace", "the or an")).isEmpty();
        verifyNoInteractions(repositories);
        when(repositories.requireLocalRepository("workspace")).thenThrow(WorkspaceException.repositoryNotReady());
        assertThat(context.find("workspace", "ResourceManager")).isEmpty();
        doReturn(workspace()).when(repositories).requireLocalRepository("workspace");
        command("init", "-q");
        Files.writeString(repository.resolve("manager.rs"), "// ResourceManager é漢字\n".repeat(4000));
        command("add", ".");
        commit();
        assertThat(context.find("workspace", "ResourceManager")).as("a local HEAD is not the monitored baseline").isEmpty();
        command("update-ref", "refs/remotes/upstream/main", "HEAD");
        assertThat(context.find("workspace", "ResourceManager")).hasValueSatisfying(found -> {
            assertThat(found.text().getBytes(UTF_8)).hasSizeLessThanOrEqualTo(GoalSourceContext.MAX_EXCERPT);
            assertThat(found.text()).contains("manager.rs:1:", "é漢字").doesNotContain("�");
            assertThat(found.truncated()).isTrue();
        });
        assertThat(context.find("workspace", "CompletelyAbsentSymbol")).isEmpty();
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext("workspace", repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), RepoRef.of("trinodb", "trino"),
                "upstream", "origin", "main", ImmutableList.of());
    }

    private void commit()
    {
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("%s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
