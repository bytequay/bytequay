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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReasoningEffortService
{
    @TempDir
    private Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ReasoningEffortService efforts;
    private WorkModel engine;

    @BeforeEach
    void setUp()
            throws Exception
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("effort.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE threads(
                    id TEXT PRIMARY KEY,
                    turn_version TEXT NOT NULL,
                    work_model_json TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE tasks(
                    id TEXT PRIMARY KEY,
                    thread_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL,
                    work_model_json TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_creation_context(
                    task_id TEXT PRIMARY KEY,
                    work_model_snapshot TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage(
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    completed_at_ms INTEGER,
                    reasoning_effort TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_current_stage(
                    task_id TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    stage_generation INTEGER NOT NULL)
                """);

        engine = new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5", null, "minimal");
        jdbc.update("INSERT INTO threads VALUES (?, 'V2', ?)",
                "trunk-1", json(withEffort("low")));
        jdbc.update("INSERT INTO tasks VALUES (?, ?, 'V2', ?)",
                "task-1", "trunk-1", json(withEffort("medium")));
        jdbc.update("INSERT INTO task_creation_context VALUES (?, ?)",
                "task-1", json(engine));
        jdbc.update("INSERT INTO stage VALUES (?, ?, 3, NULL, 'high')",
                "stage-1", "task-1");
        jdbc.update("INSERT INTO task_current_stage VALUES (?, ?, 3)",
                "task-1", "stage-1");

        efforts = new ReasoningEffortService(
                jdbc,
                mapper,
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void resolvesTypedOwnerPrecedenceWithoutChangingTheFrozenEngine()
    {
        assertThat(efforts.forTrunk("trunk-1", engine))
                .isEqualTo(withEffort("low"));
        assertThat(efforts.forTask("trunk-1", "task-1", engine))
                .isEqualTo(withEffort("medium"));
        assertThat(efforts.forStage(
                "trunk-1", "task-1", "stage-1", engine))
                .isEqualTo(withEffort("high"));
    }

    @Test
    void setAndClearFallThroughTheOwnerCascade()
    {
        efforts.setStage("task-1", "stage-1", "MAX");
        assertThat(efforts.resolveStageEngine(
                "trunk-1", "task-1", "stage-1"))
                .isEqualTo(withEffort("max"));

        efforts.setStage("task-1", "stage-1", null);
        assertThat(efforts.resolveStageEngine(
                "trunk-1", "task-1", "stage-1"))
                .isEqualTo(withEffort("medium"));

        efforts.setTask("trunk-1", "task-1", engine, "high");
        assertThat(efforts.resolveTaskEngine("trunk-1", "task-1"))
                .isEqualTo(withEffort("high"));
        efforts.setTask("trunk-1", "task-1", engine, null);
        assertThat(efforts.resolveTaskEngine("trunk-1", "task-1"))
                .isEqualTo(withEffort("low"));
    }

    @Test
    void rejectsAStageThatIsNoLongerTheExactCurrentGeneration()
    {
        jdbc.update("""
                UPDATE task_current_stage SET stage_generation = 4
                WHERE task_id = 'task-1'
                """);

        assertThatThrownBy(() -> efforts.setStage(
                "task-1", "stage-1", "max"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current open Stage");
    }

    @Test
    void rejectsUnknownEffortValues()
    {
        assertThatThrownBy(() -> efforts.setTrunk(
                "trunk-1", engine, "turbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported reasoning effort");
    }

    private WorkModel withEffort(String effort)
    {
        return new WorkModel(
                engine.kind(), engine.agentOrProvider(), engine.model(),
                engine.account(), effort);
    }

    private String json(WorkModel model)
            throws Exception
    {
        return mapper.writeValueAsString(model);
    }
}
