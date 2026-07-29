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

import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.BaseSource;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisionSourceEvidence;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisionSourceProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteProvisionTaskOperationStore
{
    private static final ObjectMapper JSON = new ObjectMapper();

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
                new SqliteProvisionTaskOperationStore(jdbc, JSON)
                        .requireByOperationId("operation-1");
        ProvisionTaskOperationHandler.ProvisionRequest restarted =
                new SqliteProvisionTaskOperationStore(jdbc(database), JSON)
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
                new SqliteProvisionTaskOperationStore(jdbc, JSON);

        assertThatThrownBy(() -> store.requireByOperationId("operation-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 2");
        assertThatThrownBy(() -> store.requireByOperationId("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 0");
    }

    @Test
    void loadsOnlyTypedPriorAttemptSourceProof()
            throws Exception
    {
        JdbcTemplate jdbc = jdbc(tempDir.resolve("source-proof.db"));
        createSchema(jdbc);
        Path root = tempDir.resolve("repo").toAbsolutePath().normalize();
        seedPlanningSnapshot(jdbc, root);
        jdbc.update("""
                INSERT INTO dispatch_ticket VALUES (
                    'ticket-1', 'operation-1', 'PROVISION_TASK', 4)
                """);
        jdbc.update("INSERT INTO agent_execution VALUES ('execution-1', 'ticket-1', 1)");
        jdbc.update("INSERT INTO agent_execution VALUES ('execution-2', 'ticket-1', 2)");
        jdbc.update("INSERT INTO agent_execution VALUES ('execution-3', 'ticket-1', 3)");
        jdbc.update("INSERT INTO agent_execution VALUES ('execution-4', 'ticket-1', 4)");
        ProvisionSourceEvidence prior = sourceEvidence(
                "execution-1", root, "base-sha");
        ProvisionSourceEvidence current = sourceEvidence(
                "execution-4", root, "different-current-sha");
        jdbc.update(
                "INSERT INTO agent_execution_log VALUES (?, 0, ?)",
                "execution-1", JSON.writeValueAsString(prior));
        jdbc.update(
                "INSERT INTO agent_execution_log VALUES (?, 0, ?)",
                "execution-2", "{\"event\":\"provider-output\"}");
        jdbc.update(
                "INSERT INTO agent_execution_log VALUES (?, 0, ?)",
                "execution-3", "not-json");
        jdbc.update(
                "INSERT INTO agent_execution_log VALUES (?, 0, ?)",
                "execution-4", JSON.writeValueAsString(current));

        ProvisionSourceProof proof = new SqliteProvisionTaskOperationStore(jdbc, JSON)
                .findPriorSourceProof("operation-1")
                .orElseThrow();

        assertThat(proof.executionId()).isEqualTo("execution-1");
        assertThat(proof.infrastructureAttempt()).isEqualTo(1);
        assertThat(proof.evidence()).isEqualTo(prior);
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
                    source_id TEXT,
                    pr_number INTEGER,
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
                CREATE TABLE review_build_selection(
                    thread_id TEXT PRIMARY KEY,
                    review_pass_id TEXT NOT NULL,
                    pr_number INTEGER NOT NULL,
                    reviewed_head_sha TEXT NOT NULL,
                    base_repository_id TEXT NOT NULL,
                    head_repository_id TEXT NOT NULL,
                    base_ref TEXT NOT NULL,
                    head_ref TEXT NOT NULL,
                    selection_digest TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE review_build_selection_item(
                    thread_id TEXT NOT NULL,
                    review_pass_id TEXT NOT NULL,
                    finding_id TEXT NOT NULL,
                    finding_revision INTEGER NOT NULL,
                    content_digest TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_assignment_review_finding(
                    assignment_id TEXT NOT NULL,
                    source_review_id TEXT NOT NULL,
                    finding_id TEXT NOT NULL,
                    finding_revision INTEGER NOT NULL,
                    content_digest TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE review_findings(
                    id TEXT PRIMARY KEY,
                    review_pass_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    status TEXT NOT NULL)
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
        jdbc.execute("""
                CREATE TABLE dispatch_ticket(
                    id TEXT PRIMARY KEY,
                    operation_id TEXT NOT NULL,
                    operation_kind TEXT NOT NULL,
                    infrastructure_attempts INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_execution(
                    id TEXT PRIMARY KEY,
                    ticket_id TEXT NOT NULL,
                    infrastructure_attempt INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_execution_log(
                    execution_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    payload TEXT NOT NULL)
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

    private static ProvisionSourceEvidence sourceEvidence(
            String executionId,
            Path repositoryRoot,
            String sha)
    {
        return new ProvisionSourceEvidence(
                ProvisionTaskOperationHandler.SOURCE_PROOF_SCHEMA,
                executionId,
                "operation-1",
                1,
                "task-1",
                1,
                BaseSource.PLANNING_SNAPSHOT,
                null,
                "acme/widget",
                "acme/widget",
                "main",
                null,
                null,
                "dev/task-1",
                repositoryRoot.resolve(".worktrees/task-1").toString(),
                sha,
                sha);
    }
}
