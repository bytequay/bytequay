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

    /**
     * A run whose turn still holds a writer closes anyway, and leaves the Task
     * and the checkout alone.
     *
     * <p>This is the case that used to fail outright: the teardown asked the
     * Task to cancel, {@code TaskRuntime} refused because a writer was live,
     * and the whole close came back as a conflict — so the one run a user most
     * wants to stop was the one run that could not be stopped.
     */
    @Test
    void aRunHeldByALiveWriterStillCloses()
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
        // Closed regardless — that is what the user asked for.
        assertThat(upstreamSync.run(run.runId()).orElseThrow().state())
                .isEqualTo(RunState.CANCELED);
        // And nothing was taken from under the process still running: the
        // Task's lifecycle is exactly where the writer left it.
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(held.status())
                .isNotEqualTo(TaskStatus.CANCELED);
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
