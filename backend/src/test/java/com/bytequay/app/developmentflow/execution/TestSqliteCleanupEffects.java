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
package com.bytequay.app.developmentflow.execution;

import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.CleanupTarget;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.EffectOutcome;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Requirement;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepKind;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepStatus;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupEffects;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestSqliteCleanupEffects
{
    @TempDir
    private Path tempDir;

    @Test
    void providerLivenessIgnoresCleanupExecutionsAndFinishedHistory()
            throws Exception
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("cleanup-effects.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE dispatch_ticket (
                    id TEXT PRIMARY KEY, task_id TEXT, async_family TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_execution (
                    id TEXT PRIMARY KEY, ticket_id TEXT, status TEXT,
                    finished_at_ms INTEGER)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(id, task_id, async_family)
                VALUES ('cleanup-ticket', 'task', 'CLEANUP'),
                       ('agent-ticket', 'task', 'AGENT_TURN')
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, status, finished_at_ms)
                VALUES ('cleanup-execution', 'cleanup-ticket', 'RUNNING', NULL),
                       ('finished-history', 'agent-ticket', 'UNKNOWN', 100)
                """);

        SqliteCleanupEffects effects = new SqliteCleanupEffects(
                jdbc, new DataSourceTransactionManager(dataSource),
                mock(GitRunner.class));
        Step providerStep = operation().steps().get(2);

        assertThat(effects.execute(
                operation(), providerStep, mock(ExecutionContext.class)).outcome())
                .isEqualTo(EffectOutcome.SUCCEEDED);

        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, status, finished_at_ms)
                VALUES ('live-provider', 'agent-ticket', 'RUNNING', NULL)
                """);
        assertThat(effects.execute(
                operation(), providerStep, mock(ExecutionContext.class)).outcome())
                .isEqualTo(EffectOutcome.INDETERMINATE);
    }

    private static Operation operation()
    {
        List<Step> steps = new ArrayList<>();
        StepKind[] kinds = StepKind.values();
        for (int index = 0; index < kinds.length; index++) {
            steps.add(new Step(
                    "step-" + (index + 1), "cleanup-row", index + 1,
                    kinds[index], Requirement.REQUIRED, StepStatus.REQUESTED,
                    0, 0, 3, null, null, null, null, null, null, null, false));
        }
        return new Operation(
                "cleanup-row", "cleanup-ticket", "task", "trunk", "workspace",
                1, 1, "cleanup-stage", 1, 1, StageCheckpoint.CLEANING,
                "terminal-acceptance", "cleanup-operation", 1,
                OperationStatus.ACTIVE, "quiescence-barrier",
                new CleanupTarget(
                        "owner/repo", "owner/repo", "/repo", "/worktree",
                        "dev/task", "origin"),
                steps);
    }
}
