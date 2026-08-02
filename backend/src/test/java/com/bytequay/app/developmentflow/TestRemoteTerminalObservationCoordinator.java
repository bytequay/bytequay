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

import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.TerminalOutcome;
import com.bytequay.app.developmentflow.stage.RemoteTerminalObservationCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteTerminalToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.TerminalContext;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.localpr.PRService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRemoteTerminalObservationCoordinator
{
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @ParameterizedTest
    @EnumSource(TerminalOutcome.class)
    void acceptedTerminalTruthProjectsStablePrStatusOnceAcrossReplay(
            TerminalOutcome outcome)
    {
        SqliteRemoteMergeRuntimeStore store = mock(
                SqliteRemoteMergeRuntimeStore.class);
        SqliteMergeOperationStore merges = mock(SqliteMergeOperationStore.class);
        RemoteTerminalToCleanupHandoff handoff = mock(
                RemoteTerminalToCleanupHandoff.class);
        PRService prs = mock(PRService.class);
        TerminalContext pending = context(outcome, null);
        TerminalContext accepted = context(outcome, "terminal-1");
        when(store.findTerminalContext("snapshot-1"))
                .thenReturn(Optional.of(pending), Optional.of(accepted),
                        Optional.of(accepted));
        when(store.findLiveMergeOperationId(
                "stage-1", "head-1", "base-1"))
                .thenReturn(Optional.empty());
        when(handoff.acceptInCommand(any())).thenReturn(
                mock(RemoteTerminalToCleanupHandoff.Result.class));
        PR drafted = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        NOW.minusSeconds(10))
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW.minusSeconds(9))
                .withRemote("acme/widget", 17,
                        "https://github.com/acme/widget/pull/17",
                        NOW.minusSeconds(8))
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW.minusSeconds(8));
        String target = outcome == TerminalOutcome.MERGED
                ? PR.STATUS_MERGED : PR.STATUS_CLOSED;
        PR terminal = drafted.withStatus(target, NOW);
        when(prs.findByTask("task-1"))
                .thenReturn(Optional.of(drafted), Optional.of(terminal));
        when(prs.transition("pr-1", target, "remote-observer"))
                .thenReturn(terminal);
        RemoteTerminalObservationCoordinator coordinator =
                new RemoteTerminalObservationCoordinator(
                        store, merges, handoff, prs);

        CommandTestSupport.executor().execute(
                "task-1", () -> coordinator.acceptInCommand("snapshot-1"));
        CommandTestSupport.executor().execute(
                "task-1", () -> coordinator.acceptInCommand("snapshot-1"));

        verify(prs, times(1)).transition(
                "pr-1", target, "remote-observer");
    }

    private static TerminalContext context(
            TerminalOutcome outcome, String terminalId)
    {
        return new TerminalContext(
                "snapshot-1", "task-1", 1, "stage-1", 1, 3,
                "head-1", "base-1", outcome, 4, 5, 1, terminalId);
    }
}
