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
package com.bytequay.app.developmentflow.execution.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteProvisionTaskOperationStore
{
    @TempDir
    private Path tempDir;

    @Test
    void loadsTheSameExactGraphAfterStoreRestart()
    {
        Path database = tempDir.resolve("provisioning.db");
        JdbcTemplate jdbc = jdbc(database);
        createSchema(jdbc);
        seedPlanningSnapshot(jdbc, tempDir.resolve("repo").toAbsolutePath().normalize());

        ProvisionTaskOperationHandler.ProvisionRequest first =
                new SqliteProvisionTaskOperationStore(jdbc)
                        .requireByOperationId("operation-1");
        ProvisionTaskOperationHandler.ProvisionRequest restarted =
                new SqliteProvisionTaskOperationStore(jdbc(database))
                        .requireByOperationId("operation-1");

        assertThat(restarted).isEqualTo(first);
        assertThat(first.taskId()).isEqualTo("task-1");
        assertThat(first.taskEpoch()).isEqualTo(1);
        assertThat(first.baseSource())
                .isEqualTo(ProvisionTaskOperationHandler.BaseSource.PLANNING_SNAPSHOT);
        assertThat(first.expectedBaseSha()).isEqualTo("base-sha");
        assertThat(first.branchName()).isEqualTo("dev/task-1");
        assertThat(first.worktreePath()).endsWith("/.worktrees/task-1");
    }

    @Test
    void missingOrNonUniqueOperationGraphFailsClosed()
    {
        JdbcTemplate jdbc = jdbc(tempDir.resolve("non-unique.db"));
        createSchema(jdbc);
        Path root = tempDir.resolve("repo").toAbsolutePath().normalize();
        seedPlanningSnapshot(jdbc, root);
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('ACME', 'WIDGET', ?)
                """, root.resolve("other").toString());
        SqliteProvisionTaskOperationStore store =
                new SqliteProvisionTaskOperationStore(jdbc);

        assertThatThrownBy(() -> store.requireByOperationId("operation-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 2");
        assertThatThrownBy(() -> store.requireByOperationId("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 0");
    }

    private static JdbcTemplate jdbc(Path file)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file);
        return new JdbcTemplate(dataSource);
    }

    private static void createSchema(JdbcTemplate jdbc)
    {
        jdbc.execute("""
                CREATE TABLE threads(
                    id TEXT PRIMARY KEY,
                    workspace_id TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE tasks(
                    id TEXT PRIMARY KEY,
                    thread_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL,
                    epoch INTEGER NOT NULL,
                    assignment_id TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE workspace_repos(
                    workspace_id TEXT NOT NULL,
                    repo_full_name TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_assignment(
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    repository_id TEXT,
                    planning_base_sha TEXT,
                    base_repository_id TEXT,
                    head_repository_id TEXT,
                    base_ref TEXT,
                    head_ref TEXT,
                    remote_base_sha TEXT,
                    remote_head_sha TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_creation_context(
                    task_id TEXT PRIMARY KEY,
                    assignment_id TEXT NOT NULL,
                    repository_id TEXT NOT NULL,
                    upstream_repository_id TEXT,
                    publish_repository_id TEXT NOT NULL,
                    base_source TEXT NOT NULL,
                    base_repository_id TEXT NOT NULL,
                    base_ref TEXT NOT NULL,
                    planning_base_sha TEXT,
                    assignment_base_sha TEXT,
                    assignment_head_sha TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_provision_target(
                    task_id TEXT PRIMARY KEY,
                    repository_id TEXT NOT NULL,
                    publish_repository_id TEXT NOT NULL,
                    branch_name TEXT NOT NULL,
                    worktree_path TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE provision_task_operation(
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    task_epoch INTEGER NOT NULL,
                    assignment_id TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    semantic_attempt INTEGER NOT NULL,
                    repository_id TEXT NOT NULL,
                    base_source TEXT NOT NULL,
                    base_repository_id TEXT NOT NULL,
                    base_ref TEXT NOT NULL,
                    expected_base_sha TEXT,
                    expected_remote_head_sha TEXT,
                    requested_branch_name TEXT NOT NULL,
                    requested_worktree_path TEXT NOT NULL,
                    status TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE watched_repos(
                    owner TEXT NOT NULL,
                    repo TEXT NOT NULL,
                    local_clone_path TEXT)
                """);
    }

    private static void seedPlanningSnapshot(JdbcTemplate jdbc, Path repositoryRoot)
    {
        String worktree = repositoryRoot.resolve(".worktrees/task-1").toString();
        jdbc.update("INSERT INTO threads VALUES ('trunk-1', 'workspace-1')");
        jdbc.update("""
                INSERT INTO workspace_repos VALUES ('workspace-1', 'acme/widget')
                """);
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, kind, planning_base_sha)
                VALUES ('assignment-1', 'NEW_FROM_TRUNK', 'base-sha')
                """);
        jdbc.update("""
                INSERT INTO tasks VALUES (
                    'task-1', 'trunk-1', 'V2', 'PROVISIONING', 1, 'assignment-1')
                """);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, repository_id,
                    publish_repository_id, base_source, base_repository_id,
                    base_ref, planning_base_sha)
                VALUES ('task-1', 'assignment-1', 'acme/widget', 'acme/widget',
                    'PLANNING_SNAPSHOT', 'acme/widget', 'main', 'base-sha')
                """);
        jdbc.update("""
                INSERT INTO task_provision_target VALUES (
                    'task-1', 'acme/widget', 'acme/widget', 'dev/task-1', ?)
                """, worktree);
        jdbc.update("""
                INSERT INTO provision_task_operation VALUES (
                    'provision-1', 'task-1', 1, 'assignment-1', 'operation-1', 1,
                    'acme/widget', 'PLANNING_SNAPSHOT', 'acme/widget', 'main',
                    'base-sha', NULL, 'dev/task-1', ?, 'DISPATCHED')
                """, worktree);
        jdbc.update("""
                INSERT INTO watched_repos VALUES ('acme', 'widget', ?)
                """, repositoryRoot.toString());
    }
}
