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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeTestSupport;
import com.bytequay.app.flow.runtime.NewFlowDatabase;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user's own stops — park, close, and the delete that follows a close.
 *
 * <p>A running sync honours a stop at a pick boundary rather than where the
 * request lands, so what these assert is mostly about what is recorded and
 * when it is allowed, not about the run halting on the spot.
 */
class TestUpstreamSyncStop
{
    private static final Instant NOW = Instant.parse("2026-08-13T10:15:30Z");

    @TempDir
    private Path temporaryDirectory;

    private UpstreamSync upstreamSync;
    private UpstreamSyncRun run;

    @BeforeEach
    void setUp()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("new-flow.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, clock).bootstrap();
        Task task = FlowRuntimeTestSupport.startTask(
                new FlowRuntime(dataSource, clock),
                "request-1",
                "repo-1",
                "Sync upstream",
                temporaryDirectory.resolve("worktree").toString());
        upstreamSync = new UpstreamSync(dataSource, new ObjectMapper(), clock);
        run = upstreamSync.startRun(
                "request-1", "repo-1", "Sync upstream", null, "upstream/main",
                "a1b2c3d", "e4f5a6b", "HEAD",
                List.of(new SelectedCommit("a1b2c3d", "Bump")),
                null, task.taskId(), 0);
        upstreamSync.advanceState(run.runId(), RunState.PICKING);
    }

    @Test
    void aRequestedParkWaitsRatherThanStoppingTheRunWhereItStands()
    {
        upstreamSync.requestPause(run.runId());

        // Still picking: the request is a record the loop reads at its next
        // boundary, not a state the run has already reached.
        assertThat(upstreamSync.pauseRequested(run.runId())).isTrue();
        assertThat(upstreamSync.run(run.runId()).orElseThrow().state())
                .isEqualTo(RunState.PICKING);
    }

    @Test
    void theParkItReachesReplacesTheRequestWithItsOwnReason()
    {
        upstreamSync.requestPause(run.runId());
        upstreamSync.park(run.runId(), "USER_PAUSED");

        assertThat(upstreamSync.pauseRequested(run.runId())).isFalse();
        UpstreamSyncRun parked = upstreamSync.run(run.runId()).orElseThrow();
        assertThat(parked.state()).isEqualTo(RunState.WAITING_USER);
        assertThat(parked.parkReason()).isEqualTo("USER_PAUSED");
    }

    @Test
    void resumingClearsTheReasonSoTheNextParkIsItsOwn()
    {
        upstreamSync.requestPause(run.runId());
        upstreamSync.park(run.runId(), "USER_PAUSED");
        upstreamSync.resume(run.runId(), 0);

        assertThat(upstreamSync.run(run.runId()).orElseThrow().parkReason())
                .isNull();
        upstreamSync.requestPause(run.runId());
        assertThat(upstreamSync.pauseRequested(run.runId())).isTrue();
    }

    @Test
    void aCloseSupersedesAParkTheRunHasNotReachedYet()
    {
        upstreamSync.requestPause(run.runId());
        upstreamSync.requestClose(run.runId());

        // One boundary, one stop: the last thing the user asked for is what
        // the run honours there.
        assertThat(upstreamSync.closeRequested(run.runId())).isTrue();
        assertThat(upstreamSync.pauseRequested(run.runId())).isFalse();
    }

    @Test
    void aCloseDuringFinalReviewWaitsForTheReviewBoundary()
    {
        upstreamSync.advanceState(run.runId(), RunState.FINAL_REVIEW);

        upstreamSync.requestClose(run.runId());

        assertThat(upstreamSync.closeRequested(run.runId())).isTrue();
        assertThat(upstreamSync.run(run.runId()).orElseThrow().state())
                .isEqualTo(RunState.FINAL_REVIEW);
    }

    @Test
    void onlyAClosedRunIsDroppedFromTheList()
    {
        assertThatThrownBy(() -> upstreamSync.delete(run.runId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only a closed upstream sync run");

        upstreamSync.advanceState(run.runId(), RunState.CANCELED);
        upstreamSync.delete(run.runId());

        assertThat(upstreamSync.run(run.runId())).isEmpty();
        // The request goes with it; nothing is left to replay the run from.
        assertThat(upstreamSync.requestForKey("request-1")).isEmpty();
    }

    @Test
    void onlyARunningRunCanBeAskedToPark()
    {
        // A run already carrying a reason has one stop pending; a second
        // request would overwrite why it is about to stop.
        upstreamSync.requestPause(run.runId());
        assertThatThrownBy(() -> upstreamSync.requestPause(run.runId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only a running upstream sync run");

        upstreamSync.park(run.runId(), "USER_PAUSED");
        assertThatThrownBy(() -> upstreamSync.requestPause(run.runId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(upstreamSync.run(run.runId()).orElseThrow().parkReason())
                .isEqualTo("USER_PAUSED");
    }
}
