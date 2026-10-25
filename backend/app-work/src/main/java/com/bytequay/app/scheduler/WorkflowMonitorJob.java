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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Selects bounded workflow batches and cleanup retries; each service owns its transitions. */
@Component
public class WorkflowMonitorJob
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowMonitorJob.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration TERMINAL_RETRY_DELAY = Duration.ofMinutes(1);

    private final CherryPickExecutionStore cherryPicks;
    private final CherryPickExecutionService cherryPickService;
    private final CiAutofixStore ciRuns;
    private final CiAutofixService ciService;
    private final BranchRebaseStore rebases;
    private final BranchRebaseService rebaseService;
    private final BackportChainStore chains;
    private final BackportChainService chainService;
    private final AgentTaskStore tasks;
    private final AgentServingGate servingGate;
    private final Clock clock;
    private volatile Instant cherryPicksRetriedAt = Instant.MIN;
    // By workflow id, the last failure message: a workflow that keeps failing the same way logs its stack once.
    private final Map<Object, String> failures = new ConcurrentHashMap<>();

    public WorkflowMonitorJob(
            CherryPickExecutionStore cherryPicks, CherryPickExecutionService cherryPickService,
            CiAutofixStore ciRuns, CiAutofixService ciService,
            BranchRebaseStore rebases, BranchRebaseService rebaseService,
            BackportChainStore chains, BackportChainService chainService,
            AgentTaskStore tasks, AgentServingGate servingGate, Clock clock)
    {
        this.cherryPicks = requireNonNull(cherryPicks, "cherryPicks is null");
        this.cherryPickService = requireNonNull(cherryPickService, "cherryPickService is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.ciService = requireNonNull(ciService, "ciService is null");
        this.rebases = requireNonNull(rebases, "rebases is null");
        this.rebaseService = requireNonNull(rebaseService, "rebaseService is null");
        this.chains = requireNonNull(chains, "chains is null");
        this.chainService = requireNonNull(chainService, "chainService is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT5S", scheduler = "workflowScheduler")
    public void monitorCherryPicks()
    {
        if (!servingGate.isReady()) {
            return;
        }
        advanceEach("cherry-pick execution", cherryPicks.listRunning(BATCH_SIZE).stream().map(CherryPickExecution::id).collect(toImmutableList()),
                cherryPickService::advance);
        // The retry selects only tasks idle for the retry delay, so looking every five seconds finds the same ones.
        Instant now = clock.instant();
        if (now.isBefore(cherryPicksRetriedAt.plus(TERMINAL_RETRY_DELAY))) {
            return;
        }
        cherryPicksRetriedAt = now;
        retryFinished("cherry-pick execution", AgentPermissionPolicy.CHERRY_PICK, BATCH_SIZE,
                taskId -> cherryPicks.findByAgentTaskId(taskId).filter(run -> run.status() == CherryPickExecution.Status.FINISHED).map(CherryPickExecution::id),
                cherryPickService::advance);
    }

    @Scheduled(initialDelayString = "${bytequay.ci-autofix.monitor-initial-delay:PT30S}", fixedDelayString = "PT30S", scheduler = "ciAutofixScheduler")
    public void monitorCiAutofix()
    {
        if (!servingGate.isReady()) {
            return;
        }
        advanceEach("CI Autofix run", ciRuns.listNonFinished(BATCH_SIZE).stream().map(CiAutofixRun::id).collect(toImmutableList()), ciService::advance);
        retryFinished("CI Autofix run", AgentPermissionPolicy.CI_AUTOFIX, BATCH_SIZE,
                taskId -> ciRuns.findByAgentTaskId(taskId).filter(run -> run.scope().parentTaskId().isEmpty())
                        .filter(run -> run.status() == CiAutofixRun.Status.FINISHED).map(CiAutofixRun::id), ciService::advance);
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT5S", scheduler = "workflowScheduler")
    public void monitorRebases()
    {
        if (servingGate.isReady()) {
            advanceEach("branch rebase", rebases.listIndependentlyRunning(BATCH_SIZE).stream().map(BranchRebase::id).collect(toImmutableList()), rebaseService::advance);
        }
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT5S", scheduler = "workflowScheduler")
    public void monitorBackports()
    {
        if (servingGate.isReady()) {
            advanceEach("backport chain", chains.list().stream()
                    .filter(chain -> chain.state() == BackportChain.State.ACTIVE || chain.state() == BackportChain.State.STOPPING)
                    .sorted(Comparator.comparing(BackportChain::updatedAt)).limit(20).map(BackportChain::id).collect(toImmutableList()), chainService::advance);
        }
    }

    private <I> void retryFinished(String what, AgentPermissionPolicy policy, int batchSize, Function<String, Optional<I>> findFinished, Consumer<I> advance)
    {
        List<AgentTask> live = tasks.listLive().stream()
                .filter(task -> task.permissionPolicy() == policy)
                .filter(task -> !task.updatedAt().isAfter(retryBefore()))
                .collect(toImmutableList());
        advanceEach(what, finishedIds(live, batchSize, findFinished), advance);
        // Advancing a live task may make it eligible for cleanup, so read the next batch afterwards.
        advanceEach(what, finishedIds(tasks.listPendingWorktreeCleanup(policy, retryBefore()), batchSize, findFinished), advance);
    }

    private static <I> List<I> finishedIds(List<AgentTask> tasks, int batchSize, Function<String, Optional<I>> findFinished)
    {
        return tasks.stream().map(task -> findFinished.apply(task.id().toString())).flatMap(Optional::stream).limit(batchSize).collect(toImmutableList());
    }

    private Instant retryBefore()
    {
        return clock.instant().minus(TERMINAL_RETRY_DELAY);
    }

    /** One failed transition must not prevent another selected workflow from advancing. */
    private <I> void advanceEach(String what, List<I> ids, Consumer<I> advance)
    {
        for (I id : ids) {
            try {
                advance.accept(id);
                failures.remove(id);
            }
            catch (RuntimeException failure) {
                String message = String.valueOf(failure.getMessage());
                if (message.equals(failures.put(id, message))) {
                    log.debug("Cannot advance {} {}: {}", what, id, message);
                }
                else {
                    log.warn("Cannot advance {} {}", what, id, failure);
                }
            }
        }
    }
}
