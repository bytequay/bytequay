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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which states a close can be left waiting in.
 *
 * <p>A close is honoured by the pick loop, so treating a state the loop is not
 * running in as "a turn may be in flight" records a request nothing will ever
 * read — the run then reports that it is closing, forever.
 */
class TestUpstreamSyncCloseReach
{
    private static final Instant NOW = Instant.parse("2026-08-13T10:15:30Z");

    @TempDir
    private Path temporaryDirectory;

    /** A held writer keeps the run out of CLOSED until cancellation settles. */
    @Test
    void aRunHeldByALiveWriterDoesNotReportClosed()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("new-flow.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, clock).bootstrap();
        FlowRuntime runtime = new FlowRuntime(dataSource, clock);
        Task task = FlowRuntimeTestSupport.startTask(
                runtime, "request-1", "repo-1", "Sync upstream",
                temporaryDirectory.resolve("worktree").toString());
        UpstreamSync upstreamSync = new UpstreamSync(
                dataSource, new ObjectMapper(), clock);
        UpstreamSyncRun run = upstreamSync.startRun(
                "request-1", "repo-1", "Sync upstream", null, "upstream/main",
                "a1b2c3d", "e4f5a6b", "HEAD",
                List.of(new SelectedCommit("a1b2c3d", "Bump")),
                null, task.taskId(), 0);
        // The state the wedged run was found in: a turn selected as writer and
        // never finished.
        new JdbcTemplate(dataSource).update(
                "UPDATE flow_runtime_task SET selected_writer_operation_id = ?"
                        + " WHERE task_id = ?",
                "operation-stuck", task.taskId());
        Task held = runtime.task(task.taskId()).orElseThrow();

        boolean released = UpstreamSyncTeardown.close(
                runtime,
                new TaskProvisioning(dataSource, runtime, ignored -> {
                    throw new AssertionError("catalog consulted");
                }, clock),
                upstreamSync,
                held,
                run.runId(),
                "UPSTREAM_SYNC_CLOSED");

        assertThat(released).isFalse();
        // CLOSED is the cleanup receipt, so a held resource keeps the run open.
        assertThat(upstreamSync.run(run.runId()).orElseThrow().state())
                .isEqualTo(RunState.READY);
        // And nothing was taken from under the process still running: the
        // Task's lifecycle is exactly where the writer left it.
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(held.status())
                .isNotEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void closeCancelsTheActiveTurnBeforeReleasingEveryLocalResource()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("close.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, clock).bootstrap();
        FlowRuntime runtime = new FlowRuntime(dataSource, clock);
        Task task = FlowRuntimeTestSupport.startTask(
                runtime, "request-2", "repo-1", "Sync upstream",
                temporaryDirectory.resolve("worktree-2").toString());
        UpstreamSync upstreamSync = new UpstreamSync(
                dataSource, new ObjectMapper(), clock);
        UpstreamSyncRun run = upstreamSync.startRun(
                "request-2", "repo-1", "Sync upstream", null,
                "upstream/main", "a1b2c3d", "e4f5a6b", "HEAD",
                List.of(new SelectedCommit("a1b2c3d", "Bump")),
                null, task.taskId(), 0);
        upstreamSync.advanceState(run.runId(), RunState.PICKING);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "UPDATE flow_runtime_task SET selected_writer_operation_id = ?"
                        + " WHERE task_id = ?",
                "operation-active", task.taskId());

        TaskProvisioning provisioning = mock(TaskProvisioning.class);
        when(provisioning.releaseWorktree(any(Task.class))).thenReturn(true);
        InitialTaskDispatcher initialTasks = mock(InitialTaskDispatcher.class);
        when(initialTasks.cancelActiveTask(task.taskId())).thenAnswer(ignored -> {
            jdbc.update(
                    "UPDATE flow_runtime_task "
                            + "SET selected_writer_operation_id = NULL "
                            + "WHERE task_id = ?",
                    task.taskId());
            return true;
        });
        UpstreamSyncCommands commands = new UpstreamSyncCommands(
                provisioning,
                mock(TaskProvisioning.RepositoryCatalog.class),
                upstreamSync,
                runtime,
                dataSource,
                mock(NewFlowDispatcher.class),
                initialTasks);

        assertThat(commands.close(run.runId())).isTrue();

        verify(initialTasks).cancelActiveTask(task.taskId());
        verify(provisioning).releaseWorktree(any(Task.class));
        Task closed = runtime.task(task.taskId()).orElseThrow();
        assertThat(closed.status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(closed.selectedWriterOperationId()).isNull();
        assertThat(runtime.writerFence(task.taskId())).isEmpty();
        assertThat(runtime.hasClaimedOperation(task.taskId())).isFalse();
        assertThat(upstreamSync.run(run.runId()).orElseThrow().state())
                .isEqualTo(RunState.CANCELED);
        assertThat(upstreamSync.closeRequested(run.runId())).isFalse();
    }

    @Test
    void deleteRetiresADeadPreviousJvmWriterBeforeForgettingTheRun()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("delete.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, clock).bootstrap();
        FlowRuntime runtime = new FlowRuntime(dataSource, clock);
        Task task = FlowRuntimeTestSupport.startTask(
                runtime, "request-dead", "repo-1", "Sync upstream",
                temporaryDirectory.resolve("worktree-dead").toString());
        UpstreamSync upstreamSync = new UpstreamSync(
                dataSource, new ObjectMapper(), clock);
        UpstreamSyncRun run = upstreamSync.startRun(
                "request-dead", "repo-1", "Sync upstream", null,
                "upstream/main", "a1b2c3d", "e4f5a6b", "HEAD",
                List.of(new SelectedCommit("a1b2c3d", "Bump")),
                null, task.taskId(), 0);
        upstreamSync.advanceState(run.runId(), RunState.PICKING);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String operation = "operation-dead";
        String session = "session-dead";
        String agentRun = "agent-run-dead";
        String attempt = "process-attempt-dead";
        jdbc.update(
                """
                INSERT INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, state, attempt, created_at
                ) VALUES (?, 'TASK', ?, ?, 'RUN_TASK_TURN', 'subject',
                          'input', 'CLAIMED', 1, ?)
                """,
                operation, task.taskId(), task.taskId(),
                NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_dispatch_ticket (
                    operation_id, not_before, claim_owner, claim_expires_at,
                    claim_generation, claim_token, priority, delivery_state
                ) VALUES (?, ?, 'dead-jvm', ?, 1, 'claim', 700, 'CLAIMED')
                """,
                operation, NOW.toEpochMilli(),
                NOW.plusSeconds(7_200).toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, last_run_id,
                    created_at, updated_at
                ) VALUES (?, ?, 'TASK_AGENT', 'RUNNING', ?, ?, ?)
                """,
                session, task.taskId(), agentRun,
                NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    wake_kind, intended_gate_kind, state, created_at,
                    started_at
                ) VALUES (?, ?, ?, 'TASK_AGENT', 'head', 'prompt',
                          'capabilities', 'input', 'INITIAL_TASK',
                          'INITIAL_PUBLISH', 'RUNNING', ?, ?)
                """,
                agentRun, operation, session,
                NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_process_attempt (
                    process_attempt_id, run_id, operation_id,
                    claim_generation, claim_token_digest, execution_id,
                    capability_id, state, jvm_pid, jvm_started_at,
                    thread_id, thread_name, agent_pid, agent_pgid,
                    agent_started_at, reserved_at, activated_at
                ) VALUES (?, ?, ?, 1, 'digest', 'execution-dead',
                          'capability-dead', 'ACTIVATED', 99999999, ?,
                          1, 'dead-thread', 99999998, 99999997, ?, ?, ?)
                """,
                attempt, agentRun, operation,
                NOW.toEpochMilli(), NOW.toEpochMilli(),
                NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_upstream_pick (
                    pick_id, run_id, ordinal, upstream_sha, pre_head,
                    result_head, result_commit_sha, state,
                    conflicted_paths_json, provenance_verified, recorded_at
                ) VALUES ('pick-dead', ?, 0, 'a1b2c3d', 'pre', 'head',
                          'commit', 'CLEAN', '[]', 1, ?)
                """,
                run.runId(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_upstream_fixup (
                    fixup_id, run_id, pick_id, owner_upstream_sha, kind,
                    current_commit_sha, changed_paths_json,
                    created_by_run_id, amend_count, recorded_at
                ) VALUES ('fixup-dead', ?, 'pick-dead', 'a1b2c3d',
                          'ADJACENT_FIXUP', 'commit', '[]', ?, 0, ?)
                """,
                run.runId(), agentRun, NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_writer_lease (
                    task_id, operation_id, task_epoch, holder_kind,
                    fencing_token, claim_generation, claim_token_digest,
                    head_sha, tree_digest, snapshot_evidence_ref, expires_at
                ) VALUES (?, ?, 1, 'TASK_AGENT', 1, 1, 'digest',
                          'head', 'tree', 'evidence', ?)
                """,
                task.taskId(), operation,
                NOW.plusSeconds(7_200).toEpochMilli());
        jdbc.update(
                """
                UPDATE flow_runtime_task
                SET status = 'ACTIVE', task_session_id = ?,
                    selected_writer_operation_id = ?
                WHERE task_id = ?
                """,
                session, operation, task.taskId());

        TaskProvisioning provisioning = mock(TaskProvisioning.class);
        when(provisioning.releaseWorktree(any(Task.class))).thenReturn(true);
        UpstreamSyncCommands commands = new UpstreamSyncCommands(
                provisioning,
                mock(TaskProvisioning.RepositoryCatalog.class),
                upstreamSync,
                runtime,
                dataSource,
                mock(NewFlowDispatcher.class),
                mock(InitialTaskDispatcher.class));

        commands.delete(run.runId());

        verify(provisioning).releaseWorktree(any(Task.class));
        assertThat(upstreamSync.run(run.runId())).isEmpty();
        assertThat(upstreamSync.requestForKey("request-dead")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_upstream_pick WHERE run_id = ?",
                Integer.class, run.runId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_upstream_fixup WHERE run_id = ?",
                Integer.class, run.runId())).isZero();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.CANCELED);
        assertThat(runtime.writerFence(task.taskId())).isEmpty();
        assertThat(runtime.hasClaimedOperation(task.taskId())).isFalse();
        assertThat(runtime.activeDispatchCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT quarantine_reason "
                        + "FROM flow_runtime_agent_process_attempt "
                        + "WHERE process_attempt_id = ?",
                String.class, attempt))
                .isEqualTo("IN_PROCESS_OWNER_UNAVAILABLE");
    }

    @Test
    void onlyTheStatesThePickLoopRunsInCanHoldACloseBack()
    {
        // The loop advances to PICKING as its first act, so a run still in
        // READY has not reached the boundary check: its close has to be taken
        // where it was asked for.
        assertThat(UpstreamSyncCommands.mayHaveATurnInFlight(RunState.READY))
                .isFalse();

        assertThat(UpstreamSyncCommands.mayHaveATurnInFlight(RunState.PICKING))
                .isTrue();
        assertThat(UpstreamSyncCommands.mayHaveATurnInFlight(
                RunState.WAITING_CONFLICT_REPAIR)).isTrue();
    }

    @Test
    void aRunStandingStillHoldsNothingAndClosesWhereItIsAsked()
    {
        for (RunState state : new RunState[] {
                RunState.WAITING_USER,
                RunState.FINAL_REVIEW,
                RunState.WAITING_INITIAL_PUBLISH,
                RunState.HANDED_OFF,
                RunState.NEEDS_ATTENTION,
                RunState.CANCELED}) {
            assertThat(UpstreamSyncCommands.mayHaveATurnInFlight(state))
                    .as("%s", state)
                    .isFalse();
        }
    }
}
