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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRelatedWorkspaceContext
{
    @TempDir
    Path repository;

    @Test
    void suppliesExactLocalCommitsAndVersionInstructionsWithoutMovingTheRelatedCheckout()
            throws Exception
    {
        GitCommandRunner git = new GitCommandRunner();
        command(git, "init", "-q");
        configureUser(repository, "Test", "test@example.com");
        Files.writeString(repository.resolve("API.txt"), "old API");
        command(git, "add", "API.txt");
        command(git, "commit", "-qm", "Old API");
        String old = command(git, "rev-parse", "HEAD");
        command(git, "tag", "v1");
        Files.writeString(repository.resolve("API.txt"), "new API");
        command(git, "commit", "-qam", "New API");
        String next = command(git, "rev-parse", "HEAD");
        command(git, "update-ref", "refs/remotes/origin/main", next);
        var relationships = mock(WorkspaceRelationships.class);
        var repositories = mock(WorkspaceRepositoryService.class);
        Workspace workspace = new Workspace("iceberg", "Iceberg", 2, RepoRef.of("apache", "iceberg"), Optional.empty(), Optional.empty(),
                2, RepoRef.of("apache", "iceberg"), Workspace.Topology.DIRECT, "main", "https://github.com/apache/iceberg.git",
                Optional.empty(), Optional.empty(), "/managed/iceberg", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
        when(relationships.related("repository")).thenReturn(ImmutableList.of(
                new WorkspaceRelationships.Related("iceberg", WorkspaceRelationships.Position.DEPENDENCY, "Version in pom.xml")));
        when(relationships.requireRelated("repository", "iceberg")).thenReturn(workspace);
        when(repositories.requireLocalRepository("iceberg")).thenReturn(new WorkspaceRepositoryContext("iceberg", repository, 2,
                RepoRef.of("apache", "iceberg"), OptionalLong.empty(), RepoRef.of("apache", "iceberg"), "origin", "origin", "main", ImmutableList.of()));
        var context = new RelatedWorkspaceContext(relationships, repositories, mock(AgentReviewStore.class), git, new ObjectMapper());
        AgentTask task = AgentTask.created(TaskId.random(), Optional.of("repository"), true, "Upgrade Iceberg", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repository"), repository,
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty(), Optional.empty()), Instant.EPOCH);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "user", new AgentTurnSettings(Optional.empty(), Optional.empty(),
                AgentPermissionPolicy.AUTO, Duration.ofHours(1), ImmutableList.of(), Optional.empty(), Optional.empty()), Instant.EPOCH);
        String input = context.append(task, turn, "Investigate the upgrade");
        assertThat(input).startsWith("Investigate the upgrade").contains(repository.toString(), next, "Version in pom.xml", "apache/iceberg", "DEPENDENCY")
                .doesNotContain("SEARCH/READ");
        assertThat(context.instructions(task)).contains("NOT the version this project uses", "unknown mappings", "SEARCH/READ", "Memory is current advice", "verify its sources");
        // A generated follow-up continues the turn that already listed the links.
        AgentTurn followUp = turn.withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, TurnId.random()));
        assertThat(context.append(task, followUp, "Commit the change")).isEqualTo("Commit the change");
        String mappedVersion = command(git, "rev-parse", "v1^{commit}");
        command(git, "tag", "-f", "v1", next);
        assertThat(command(git, "rev-parse", "v1^{commit}")).isEqualTo(next);
        assertThat(mappedVersion).isEqualTo(old);
        assertThat(command(git, "show", mappedVersion + ":API.txt")).isEqualTo("old API");
        assertThat(command(git, "diff", old, next, "--", "API.txt")).contains("-old API", "+new API");
        assertThat(command(git, "rev-parse", "HEAD")).isEqualTo(next);
        command(git, "update-ref", "-d", "refs/remotes/origin/main");
        assertThat(context.append(task, turn, "Investigate")).contains("unavailable", "synchronize").doesNotContain("\"commit\":");
        when(relationships.related("repository")).thenReturn(ImmutableList.of());
        assertThat(context.append(task, turn, "No related workspaces")).isEqualTo("No related workspaces");
        assertThat(context.instructions(task)).isEmpty();
    }

    private String command(GitCommandRunner git, String... args)
    {
        var result = git.run(repository, args);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output().strip();
    }
}
