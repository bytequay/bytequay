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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkspaceRootMove
{
    @TempDir
    private Path temporary;

    private final GitCommandRunner git = new GitCommandRunner();
    private Path legacy;
    private Path root;
    private Path ideProfiles;
    private String url;

    @BeforeEach
    void setUp()
            throws IOException, SQLException
    {
        Path data = Files.createDirectories(temporary.toRealPath().resolve("Application Support/ByteQuay"));
        legacy = data.resolve("workspaces");
        root = data.getParent().getParent().resolve(".bytequay/workspaces");
        ideProfiles = data.resolve("agent-runtime/ide");
        url = "jdbc:sqlite:" + data.resolve("bytequay.db");

        Path repository = Files.createDirectories(legacy.resolve("ws/repository"));
        run(repository, "init", "-q", "-b", "main");
        run(repository, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-q", "-m", "Initial");
        run(repository, "worktree", "add", "-q", "-b", "t1", legacy.resolve("ws/worktrees/t1").toString());
        run(repository, "worktree", "add", "-q", "-b", "t2", legacy.resolve("ws/worktrees/t2").toString());
        Files.createDirectories(legacy.resolve("ws/worktrees/leftover"));
        Files.createDirectories(ideProfiles.resolve("t1/commit"));
        Files.writeString(ideProfiles.resolve("t1/commit/java-home"), "/jdk");
        Files.writeString(ideProfiles.resolve("t1/commit/connection-token"), "token");

        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE watched_repo_workspaces (id TEXT, local_repository_path TEXT)");
            statement.execute("CREATE TABLE agent_task (id TEXT, worktree TEXT)");
            statement.execute("CREATE TABLE branch_rebase (id TEXT, worktree TEXT)");
            statement.execute("INSERT INTO watched_repo_workspaces VALUES ('ws', '%s/ws/repository')".formatted(legacy));
            statement.execute("INSERT INTO agent_task VALUES ('t1', '%1$s/ws/worktrees/t1'), ('t2', '%1$s/ws/worktrees/t2'), ('other', '%1$s-other/t3'), ('none', NULL)"
                    .formatted(legacy));
            statement.execute("INSERT INTO branch_rebase VALUES ('t1', '%s/ws/worktrees/t1')".formatted(legacy));
        }
    }

    @Test
    void movesTheWorkspacesBesideTheDatabaseWithEverythingThatNamesThem()
            throws IOException, SQLException
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", url + "?foreign_keys=ON")
                .withProperty("bytequay.workspace.root", root.toString())
                .withProperty("bytequay.agent.runtime-root", ideProfiles.getParent().toString());
        new WorkspaceRootMove().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));

        assertThat(legacy).doesNotExist();
        assertMoved();
        assertThat(ideProfiles.resolve("t1/commit/java-home")).doesNotExist();
        assertThat(ideProfiles.resolve("t1/commit/connection-token")).exists();
        assertThat(root.resolve(".move-pending")).doesNotExist();

        WorkspaceRootMove.move(legacy, root, url, ideProfiles, git);
        assertMoved();
    }

    @Test
    void finishesAMoveThatStoppedAfterTheRename()
            throws IOException, SQLException
    {
        Files.writeString(legacy.resolve(".move-pending"), "");
        Files.createDirectories(root.getParent());
        Files.move(legacy, root);

        WorkspaceRootMove.move(legacy, root, url, ideProfiles, git);

        assertMoved();
        assertThat(root.resolve(".move-pending")).doesNotExist();
    }

    @Test
    void joinsWorkspacesAnOlderBuildWroteAfterAnEarlierMove()
            throws IOException, SQLException
    {
        Files.createDirectories(root.resolve("earlier/repository"));

        WorkspaceRootMove.move(legacy, root, url, ideProfiles, git);

        assertThat(legacy).doesNotExist();
        assertMoved();
        assertThat(root.resolve("earlier/repository")).isDirectory();
        assertThat(root.resolve(".move-pending")).doesNotExist();
    }

    @Test
    void keepsAWorkspaceTheDestinationAlreadyHolds()
            throws IOException, SQLException
    {
        Files.createDirectories(root.resolve("ws"));

        WorkspaceRootMove.move(legacy, root, url, ideProfiles, git);

        assertThat(git.run(legacy.resolve("ws/worktrees/t1"), "status").successful()).isTrue();
        assertThat(column("watched_repo_workspaces", "local_repository_path")).containsExactly(Map.entry("ws", legacy + "/ws/repository"));
        assertThat(ideProfiles.resolve("t1/commit/java-home")).exists();
        assertThat(root.resolve(".move-pending")).doesNotExist();
    }

    private void assertMoved()
            throws SQLException
    {
        Path repository = root.resolve("ws/repository");
        for (String task : ImmutableList.of("t1", "t2")) {
            assertThat(git.run(root.resolve("ws/worktrees").resolve(task), "status").successful()).isTrue();
        }
        assertThat(git.run(repository, "worktree", "list", "--porcelain").output().lines().filter(line -> line.startsWith("worktree ")))
                .containsExactly("worktree " + repository, "worktree " + root.resolve("ws/worktrees/t1"), "worktree " + root.resolve("ws/worktrees/t2"));
        assertThat(column("watched_repo_workspaces", "local_repository_path")).containsExactly(Map.entry("ws", root + "/ws/repository"));
        assertThat(column("agent_task", "worktree")).containsExactlyInAnyOrderEntriesOf(ImmutableMap.of(
                "t1", root + "/ws/worktrees/t1",
                "t2", root + "/ws/worktrees/t2",
                "other", legacy + "-other/t3",
                "none", ""));
        assertThat(column("branch_rebase", "worktree")).containsExactly(Map.entry("t1", root + "/ws/worktrees/t1"));
    }

    private Map<String, String> column(String table, String column)
            throws SQLException
    {
        ImmutableMap.Builder<String, String> values = ImmutableMap.builder();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, coalesce(%s, '') FROM %s".formatted(column, table))) {
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
        }
        return values.buildOrThrow();
    }

    private void run(Path directory, String... arguments)
    {
        List<String> command = ImmutableList.copyOf(arguments);
        assertThat(git.run(directory, command).successful()).as("git %s", command).isTrue();
    }
}
