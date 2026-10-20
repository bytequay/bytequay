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
package com.bytequay.app.scheduler;

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.DevelopmentCiOwnership;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.RebaseSequencerDriver;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.agent.AgentServingGates.readyAgentServingGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestRebaseMonitoring
{
    @Test
    void oneFailureDoesNotStopAnotherRunningRebase()
    {
        BranchRebase first = rebase("first");
        BranchRebase second = rebase("second");
        FailingReadStore store = new FailingReadStore(ImmutableList.of(first, second));
        GitCommandRunner git = new GitCommandRunner();
        BranchRebaseService service = new BranchRebaseService(
                store,
                mock(AgentTaskService.class),
                mock(WorktreeService.class),
                mock(AgentTurnService.class),
                new RebaseSequencerDriver(git),
                git,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                new DevelopmentCiOwnership(mock(CiAutofixStore.class), mock(AgentTaskStore.class), mock(AgentTurnStore.class),
                        mock(PullRequestTaskStore.class), mock(PullRequestStore.class), mock(AgentEventService.class),
                        new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {}, ignored -> false, true)));

        monitor(store, service, readyAgentServingGate()).monitorRebases();

        assertThat(store.attempted()).containsExactly(first.id(), second.id());
    }

    @Test
    void doesNotAdvanceWhileAgentRecoveryIsIncomplete()
    {
        BranchRebaseStore store = mock(BranchRebaseStore.class);
        BranchRebaseService service = mock(BranchRebaseService.class);

        monitor(store, service, new AgentServingGate()).monitorRebases();

        verifyNoInteractions(store, service);
    }

    private static WorkflowMonitorJob monitor(BranchRebaseStore store, BranchRebaseService service, AgentServingGate gate)
    {
        return new WorkflowMonitorJob(mock(CherryPickExecutionStore.class), mock(CherryPickExecutionService.class), mock(CiAutofixStore.class),
                mock(CiAutofixService.class), store, service, mock(BackportChainStore.class), mock(BackportChainService.class),
                mock(AgentTaskStore.class), gate, Clock.systemUTC());
    }

    private static BranchRebase rebase(String key)
    {
        return BranchRebase.create(
                BranchRebaseId.random(),
                key,
                "task-" + key,
                RepoRef.of("trinodb", "trino"),
                Path.of("/tmp/bytequay-rebase/" + key),
                "feature-" + key,
                "main",
                "a".repeat(40),
                "b".repeat(40),
                Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static final class FailingReadStore
            implements BranchRebaseStore
    {
        private final List<BranchRebase> running;
        private final List<BranchRebaseId> attempted = new ArrayList<>();

        private FailingReadStore(List<BranchRebase> running)
        {
            this.running = running;
        }

        @Override
        public BranchRebase createOrGet(BranchRebase rebase)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BranchRebase> findByIdempotencyKey(String idempotencyKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BranchRebase> find(BranchRebaseId rebaseId)
        {
            attempted.add(rebaseId);
            if (rebaseId.equals(running.getFirst().id())) {
                throw new BranchRebaseException("broken");
            }
            return Optional.of(running.get(1).finish(BranchRebase.FinishReason.CANCELLED, "already finished", Optional.empty(), Instant.parse("2026-08-20T00:00:01Z")));
        }

        @Override
        public List<BranchRebase> listRunning(int limit)
        {
            return running;
        }

        @Override
        public List<BranchRebase> listIndependentlyRunning(int limit)
        {
            return listRunning(limit);
        }

        @Override
        public BranchRebase save(BranchRebase rebase)
        {
            throw new UnsupportedOperationException();
        }

        public List<BranchRebaseId> attempted()
        {
            return attempted;
        }
    }
}
