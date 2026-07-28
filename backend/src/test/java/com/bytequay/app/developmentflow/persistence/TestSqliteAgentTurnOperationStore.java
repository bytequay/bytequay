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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAgentTurnOperationStore
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteAgentTurnOperationStore store;

    @BeforeEach
    void setUp()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("agent-turn.db"));
        jdbc = new JdbcTemplate(dataSource);
        createQuerySchema();
        seedExactTurns();
        store = new SqliteAgentTurnOperationStore(jdbc);
    }

    @Test
    void loadsExactTaskTurnAndItsImmutableBrainIdentity()
    {
        AgentTurnOperationHandler.ExactTurn turn = store.find(
                        DispatchTicket.OwnerKind.TASK_TURN, "task-turn-1")
                .orElseThrow();

        assertThat(turn.ownerKind()).isEqualTo(DispatchTicket.OwnerKind.TASK_TURN);
        assertThat(turn.taskId()).isEqualTo("task-1");
        assertThat(turn.stageId()).isEqualTo("stage-1");
        assertThat(turn.stageGeneration()).isEqualTo(1);
        assertThat(turn.operationId()).isEqualTo("task-operation-1");
        assertThat(turn.launchInput()).isEqualTo("task-launch");
        assertThat(turn.worktreePath()).isEqualTo("/tmp/task-1");
        assertThat(turn.currentCodeFingerprint()).isEqualTo("fingerprint-1");
        assertThat(turn.brainProvider()).isEqualTo("openai");
        assertThat(turn.brainModel()).isEqualTo("gpt-5.6");
    }

    @Test
    void loadsExactStageTurnWithoutInferringItFromNullableTaskColumns()
    {
        AgentTurnOperationHandler.ExactTurn turn = store.find(
                        DispatchTicket.OwnerKind.STAGE_TURN, "stage-turn-1")
                .orElseThrow();

        assertThat(turn.ownerKind()).isEqualTo(DispatchTicket.OwnerKind.STAGE_TURN);
        assertThat(turn.taskId()).isEqualTo("task-1");
        assertThat(turn.stageId()).isEqualTo("stage-1");
        assertThat(turn.stageGeneration()).isEqualTo(1);
        assertThat(turn.operationId()).isEqualTo("stage-operation-1");
        assertThat(turn.launchInput()).isEqualTo("stage-launch");
        assertThat(turn.expectedCodeFingerprint()).isEqualTo("fingerprint-1");
        assertThat(turn.brainProvider()).isNull();
        assertThat(turn.brainModel()).isNull();
    }

    @Test
    void authorizesOnlyTheExactLiveStageTurnOperation()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");

        AgentTurnOperationHandler.McpContext context = store.authorizeMcp(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        "stage-turn-1", "stage-operation-1", now)
                .orElseThrow();

        assertThat(context.trunkId()).isEqualTo("trunk-1");
        assertThat(context.taskId()).isEqualTo("task-1");
        assertThat(context.stageId()).isEqualTo("stage-1");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'stage-turn-1'",
                String.class)).isEqualTo("RUNNING");
        assertThat(store.authorizeMcp(
                DispatchTicket.OwnerKind.STAGE_TURN,
                "stage-turn-1", "wrong-operation", now)).isEmpty();
    }

    @Test
    void releasedLeaseMakesTaskTurnMcpFailClosed()
    {
        jdbc.update("""
                UPDATE capacity_lease SET released_at_ms = 1
                WHERE id = 'task-lease-1'
                """);

        assertThat(store.authorizeMcp(
                DispatchTicket.OwnerKind.TASK_TURN,
                "task-turn-1", "task-operation-1",
                Instant.parse("2026-07-28T00:00:00Z"))).isEmpty();
    }

    @Test
    void staleTaskCannotBeMarkedRunningByAnMcpRequest()
    {
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'PAUSING' WHERE id = 'task-1'
                """);

        assertThat(store.authorizeMcp(
                DispatchTicket.OwnerKind.STAGE_TURN,
                "stage-turn-1", "stage-operation-1",
                Instant.parse("2026-07-28T00:00:00Z"))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'stage-turn-1'",
                String.class)).isEqualTo("QUEUED");
    }

    private void createQuerySchema()
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
                    epoch INTEGER NOT NULL,
                    workflow_version TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_brain(
                    task_id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_code_identity(
                    task_id TEXT PRIMARY KEY,
                    worktree_path TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage(
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    completed_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE task_current_stage(
                    task_id TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    stage_generation INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_current_code_subject_v230(
                    task_id TEXT PRIMARY KEY,
                    code_fingerprint TEXT,
                    head_sha TEXT,
                    base_sha TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_turn(
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    task_epoch INTEGER NOT NULL,
                    trigger_stage_id TEXT,
                    trigger_stage_generation INTEGER,
                    purpose TEXT NOT NULL,
                    status TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    attempt INTEGER NOT NULL,
                    expected_code_fingerprint TEXT,
                    expected_head_sha TEXT,
                    expected_base_sha TEXT,
                    launch_input TEXT NOT NULL,
                    started_at_ms INTEGER,
                    error_message TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE stage_turn(
                    id TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    task_epoch INTEGER NOT NULL,
                    stage_generation INTEGER NOT NULL,
                    purpose TEXT NOT NULL,
                    status TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    attempt INTEGER NOT NULL,
                    expected_code_fingerprint TEXT,
                    expected_head_sha TEXT,
                    expected_base_sha TEXT,
                    launch_input TEXT NOT NULL,
                    started_at_ms INTEGER,
                    error_message TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE dispatch_ticket(
                    id TEXT PRIMARY KEY,
                    operation_id TEXT NOT NULL,
                    owner_kind TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    operation_kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    capacity_lease_id TEXT,
                    task_id TEXT,
                    task_epoch INTEGER,
                    stage_id TEXT,
                    stage_generation INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE agent_execution(
                    id TEXT PRIMARY KEY,
                    ticket_id TEXT NOT NULL,
                    status TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE capacity_lease(
                    id TEXT PRIMARY KEY,
                    ticket_id TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    task_id TEXT,
                    task_epoch INTEGER,
                    released_at_ms INTEGER,
                    expires_at_ms INTEGER NOT NULL)
                """);
    }

    private void seedExactTurns()
    {
        jdbc.update("""
                INSERT INTO threads(id, workspace_id) VALUES ('trunk-1', 'workspace-1')
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, epoch, workflow_version, lifecycle_state)
                VALUES ('task-1', 'trunk-1', 1, 'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_brain(task_id, provider, model)
                VALUES ('task-1', 'openai', 'gpt-5.6')
                """);
        jdbc.update("""
                INSERT INTO task_code_identity(task_id, worktree_path)
                VALUES ('task-1', '/tmp/task-1')
                """);
        jdbc.update("""
                INSERT INTO stage(id, task_id, generation, kind, completed_at_ms)
                VALUES ('stage-1', 'task-1', 1, 'LOCAL_DEVELOPMENT', NULL)
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'stage-1', 1)
                """);
        jdbc.update("""
                INSERT INTO task_current_code_subject_v230(
                    task_id, code_fingerprint, head_sha, base_sha)
                VALUES ('task-1', 'fingerprint-1', 'head-1', 'base-1')
                """);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, task_epoch, trigger_stage_id,
                    trigger_stage_generation, purpose, status, operation_id,
                    attempt, expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, launch_input)
                VALUES ('task-turn-1', 'task-1', 1, 'stage-1', 1,
                    'BRAIN_REVIEW', 'QUEUED', 'task-operation-1', 1,
                    'fingerprint-1', 'head-1', 'base-1', 'task-launch')
                """);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, task_epoch, stage_generation, purpose, status,
                    operation_id, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, launch_input)
                VALUES ('stage-turn-1', 'stage-1', 1, 1, 'IMPLEMENT', 'QUEUED',
                    'stage-operation-1', 1, 'fingerprint-1', 'head-1',
                    'base-1', 'stage-launch')
                """);
        seedRunningTicket(
                "task-ticket-1", "task-operation-1", "TASK_TURN",
                "task-turn-1", "EXECUTE_TASK_TURN", "task-lease-1");
        seedRunningTicket(
                "stage-ticket-1", "stage-operation-1", "STAGE_TURN",
                "stage-turn-1", "EXECUTE_STAGE_TURN", "stage-lease-1");
    }

    private void seedRunningTicket(
            String ticketId,
            String operationId,
            String ownerKind,
            String ownerId,
            String operationKind,
            String leaseId)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, owner_kind, owner_id, operation_kind,
                    status, capacity_lease_id, task_id, task_epoch, stage_id,
                    stage_generation)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, 'task-1', 1,
                    'stage-1', 1)
                """, ticketId, operationId, ownerKind, ownerId, operationKind,
                leaseId);
        jdbc.update("""
                INSERT INTO agent_execution(id, ticket_id, status)
                VALUES (?, ?, 'RUNNING')
                """, "execution:" + ticketId, ticketId);
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, task_id, task_epoch,
                    released_at_ms, expires_at_ms)
                VALUES (?, ?, ?, 'task-1', 1, NULL, ?)
                """, leaseId, ticketId, operationId, Long.MAX_VALUE);
    }
}
