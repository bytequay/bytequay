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
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Moves the workspaces that lie beside the database to the configured root, before any bean can open one. They used to
 * live in the app data folder, whose "Application Support" puts a space in every checkout path, and project tests that
 * turn file paths into URLs fail on it.
 *
 * <p>ponytail: one move for installs made before the root left the app data folder; delete it once none remain.
 */
public final class WorkspaceRootMove
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceRootMove.class);
    private static final String PENDING = ".move-pending";
    private static final String SQLITE = "jdbc:sqlite:";
    private static final Map<String, String> PATH_COLUMNS = ImmutableMap.of(
            "watched_repo_workspaces", "local_repository_path",
            "agent_task", "worktree",
            "branch_rebase", "worktree",
            "cherry_pick_execution", "worktree");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event)
    {
        Environment environment = event.getEnvironment();
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (!url.startsWith(SQLITE)) {
            return;
        }
        String file = url.substring(SQLITE.length());
        int query = file.indexOf('?');
        // Only the folder beside the database it rewrites, so a run on a database of its own never moves the app's data.
        Path legacy = Path.of(query < 0 ? file : file.substring(0, query)).toAbsolutePath().getParent().resolve("workspaces");
        Path root = Path.of(environment.getRequiredProperty("bytequay.workspace.root")).toAbsolutePath().normalize();
        Path ideProfiles = Path.of(environment.getRequiredProperty("bytequay.agent.runtime-root")).resolve("ide");
        try {
            move(legacy, root, url, ideProfiles, new GitCommandRunner());
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Cannot move the workspaces from " + legacy + " to " + root, failure);
        }
        catch (SQLException failure) {
            throw new IllegalStateException("Cannot point the database at the workspaces moved to " + root, failure);
        }
    }

    static void move(Path legacy, Path root, String databaseUrl, Path ideProfiles, GitCommandRunner git)
            throws IOException, SQLException
    {
        if (legacy.equals(root)) {
            return;
        }
        if (Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root.getParent());
            // The marker moves with the folder, so a start that stops after the rename finishes the steps below the next time.
            Files.write(legacy.resolve(PENDING), new byte[0]);
            // One rename: it refuses a destination that holds anything, or one on another volume, before anything has changed.
            Files.move(legacy, root, StandardCopyOption.ATOMIC_MOVE);
            log.info("Moved the workspaces from {} to {}", legacy, root);
        }
        Path pending = root.resolve(PENDING);
        if (Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        repairWorktrees(root, git);
        rewriteRows(databaseUrl, legacy, root);
        forgetJavaProjects(ideProfiles);
        Files.delete(pending);
    }

    /** Each worktree and its repository name the other by absolute path; git rewrites both once it is given the new ones. */
    private static void repairWorktrees(Path root, GitCommandRunner git)
            throws IOException
    {
        for (Path workspace : directories(root)) {
            List<String> worktrees = directories(workspace.resolve("worktrees")).stream()
                    .map(Path::toString)
                    .collect(toImmutableList());
            if (worktrees.isEmpty()) {
                continue;
            }
            Result repaired = git.run(workspace.resolve("repository"), ImmutableList.<String>builder().add("worktree", "repair").addAll(worktrees).build());
            if (!repaired.successful()) {
                // A leftover folder that is no worktree fails the command, but git still repairs every real one listed.
                log.warn("Git could not repair every worktree of {}: {}", workspace, repaired.output().strip());
            }
        }
    }

    private static void rewriteRows(String databaseUrl, Path legacy, Path root)
            throws SQLException
    {
        String from = legacy + "/";
        int rewritten = 0;
        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            connection.setAutoCommit(false);
            for (Map.Entry<String, String> column : PATH_COLUMNS.entrySet()) {
                if (!hasTable(connection, column.getKey())) {
                    continue;
                }
                String sql = "UPDATE %1$s SET %2$s = ? || substr(%2$s, ?) WHERE substr(%2$s, 1, ?) = ?".formatted(column.getKey(), column.getValue());
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setString(1, root + "/");
                    update.setInt(2, from.length() + 1);
                    update.setInt(3, from.length());
                    update.setString(4, from);
                    rewritten += update.executeUpdate();
                }
            }
            connection.commit();
        }
        log.info("Pointed {} database rows at the moved workspaces", rewritten);
    }

    private static boolean hasTable(Connection connection, String table)
            throws SQLException
    {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            query.setString(1, table);
            try (ResultSet found = query.executeQuery()) {
                return found.next();
            }
        }
    }

    /** The Java language server records each project's absolute folder; without its runtime marker the IDE imports them afresh. */
    private static void forgetJavaProjects(Path ideProfiles)
            throws IOException
    {
        for (Path task : directories(ideProfiles)) {
            for (Path profile : directories(task)) {
                Files.deleteIfExists(profile.resolve("java-home"));
            }
        }
    }

    private static List<Path> directories(Path folder)
            throws IOException
    {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return ImmutableList.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)).collect(toImmutableList());
        }
    }
}
