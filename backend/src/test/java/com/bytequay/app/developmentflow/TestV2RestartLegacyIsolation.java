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
package com.bytequay.app.developmentflow;

import com.bytequay.app.ByteQuayApplication;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationHandoff;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.SqliteTestPools;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("StringConcatToTextBlock")
@ExtendWith(SqliteTestPools.class)
class TestV2RestartLegacyIsolation
{
    private static final String WORKSPACE = "workspace-restart";
    private static final String TRUNK = "trunk-restart";
    private static final String REPOSITORY = "acme/restart";
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void restartWithPersistedV2PlanningTaskCreatesNoLegacyStageOrTurn()
            throws Exception
    {
        Path repositoryRoot = Files.createDirectories(tempDir.resolve("repo"))
                .toAbsolutePath();
        initializeRepository(repositoryRoot);
        Path databasePath = tempDir.resolve(
                "bytequay-test-v2-planning-restart.db").toAbsolutePath();
        String url = "jdbc:sqlite:" + databasePath
                + "?foreign_keys=ON&busy_timeout=30000";
        DataSource dataSource = database(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        String taskId = createPlanningTask(dataSource, repositoryRoot);

        assertThat(jdbc.queryForObject(
                "SELECT phase FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("PLANNING");
        assertNoLegacyRuntimeRows(jdbc, taskId);

        try (ConfigurableApplicationContext ignored =
                new SpringApplicationBuilder(ByteQuayApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.datasource.url=" + url,
                                "--spring.datasource.hikari.maximum-pool-size=1",
                                "--bytequay.scheduling.enabled=false",
                                "--bytequay.codegraph.auto-install=false",
                                "--logging.file.name=" + tempDir.resolve("backend.log"),
                                "--logging.level.root=WARN",
                                "--spring.main.banner-mode=off")) {
            assertThat(ignored.isActive()).isTrue();
        }

        assertNoLegacyRuntimeRows(jdbc, taskId);
    }

    private static void initializeRepository(Path repositoryRoot)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder("git", "init", "--quiet")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor()).isZero();
    }

    private static DataSource database(String url)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .load()
                .migrate();
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static void seedTrunk(JdbcTemplate jdbc, Path repositoryRoot)
    {
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('acme', 'restart', ?)
                """, repositoryRoot.toString());
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, 'Restart workspace', '', 0, 1, 1)
                """, WORKSPACE);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES (?, ?, 'main', 0, 1)
                """, WORKSPACE, REPOSITORY);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES (?, 'CLI_AGENT', 'codex', 'Restart trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, ?, 'build', 4, 'V2', 'ACTIVE')
                """, TRUNK, WORKSPACE);
    }

    private static String createPlanningTask(
            DataSource dataSource, Path repositoryRoot)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TrunkManager.Store trunkStore;
        TaskManager.Store taskStore;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(DataSourceTransactionManager.class,
                    () -> transactionManager);
            context.scan(
                    "com.bytequay.app.developmentflow.trunk.persistence",
                    "com.bytequay.app.developmentflow.task.persistence");
            context.refresh();
            trunkStore = context.getBean(TrunkManager.Store.class);
            taskStore = context.getBean(TaskManager.Store.class);
        }

        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        TaskCreationHandoff handoff = new TaskCreationHandoff(
                commands,
                new TrunkManager(commands, trunkStore),
                new TaskManager(commands, taskStore),
                new IdGenerator(ignored -> 1));
        TaskAssignment.Identity identity = new TaskAssignment.Identity(
                "assignment-restart", TRUNK, "authorization-restart",
                "user", CREATED_AT);
        TaskAssignment assignment = new TaskAssignment.Automation(
                identity, "restart-test", "verify V2 restart isolation");
        TaskAssignment.Direct repository = new TaskAssignment.Direct(REPOSITORY);
        TaskCreationInput input = new TaskCreationInput(
                WORKSPACE,
                assignment,
                new TaskCreationInput.TaskPolicy(
                        "policy-restart", TRUNK, 1, "TRUNK",
                        false, false, 1, 3, 3, true,
                        Optional.of("permissions-restart"), "user", CREATED_AT),
                new TaskCreationInput.FreshRemoteBase(repository, "main"),
                new TaskCreationInput.EngineSnapshot(
                        "openai", "test-model", "engine-restart"),
                new TaskCreationInput.WorkModelSnapshot("work-model-restart"),
                new TaskCreationInput.Presentation(
                        "Restart isolation", "DEVELOP", null,
                        "verify restart", "user"),
                CREATED_AT);
        TaskCreationHandoff.Result created = handoff.create(
                new TaskCreationHandoff.Command(
                        new TrunkManager.TaskCreationCommand(
                                "create-restart-task", "user", 0, input),
                        repositoryRoot));
        assertThat(created.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        return created.task().task().id();
    }

    private static void assertNoLegacyRuntimeRows(
            JdbcTemplate jdbc, String taskId)
    {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_stage WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM thread_turns WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }
}
