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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.service.stage.StageStateMachine;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
class AgentRunServiceImpl
        implements AgentRunService
{
    private final AgentRunStore store;
    private final StageStateMachine stages;
    private final TaskCommandExecutor commands;
    private final Clock clock;

    @Autowired
    AgentRunServiceImpl(
            AgentRunStore store,
            StageStateMachine stages,
            TaskCommandExecutor commands)
    {
        this(store, stages, commands, Clock.systemUTC());
    }

    AgentRunServiceImpl(
            AgentRunStore store,
            StageStateMachine stages,
            TaskCommandExecutor commands,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public Optional<AgentRun> findById(String runId)
    {
        return store.findById(runId);
    }

    @Override
    public List<AgentRun> findByWorkspace(String workspaceId)
    {
        return store.findByWorkspace(workspaceId);
    }

    @Override
    public List<AgentRun> findByThread(String threadId)
    {
        return store.findByThread(threadId);
    }

    @Override
    public List<AgentRun> findByReviewRound(String reviewRoundId)
    {
        return store.findByReviewRound(reviewRoundId);
    }

    @Override
    public List<AgentRun> findByTask(String taskId, String kind, String parentStageId)
    {
        return store.findByTask(taskId, kind, parentStageId);
    }

    @Override
    public List<AgentRun> liveRunsByTask(String taskId)
    {
        return store.findLiveByTask(taskId);
    }

    @Override
    public AgentRun open(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget)
    {
        requireText(taskId, "taskId");
        requireText(kind, "kind");
        return commands.execute(taskId, () -> openInCommand(
                taskId, kind, source, parentStageId, backingStageType, budget));
    }

    @Override
    public AgentRun openInCommand(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Optional<AgentRun> existing = store.findLiveByTaskAndKind(taskId, kind);
        if (existing.isPresent()) {
            return existing.get();
        }
        StageInstance backing = stages.ensureRunOpenInCommand(
                taskId, kind, backingStageType, null);
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source, parentStageId,
                /* reviewRoundId */ null, backing.id().toString(), AgentRun.STATUS_RUNNING,
                /* iterations */ 0, budget, /* headline */ null, /* metricsJson */ null,
                now(), /* finishedAt */ null);
        return store.insert(run);
    }

    @Override
    public AgentRun openInStage(
            String taskId, String kind, String source, String stageId, Integer budget)
    {
        requireText(taskId, "taskId");
        requireText(kind, "kind");
        requireText(stageId, "stageId");
        return commands.execute(taskId,
                () -> openInStageInCommand(taskId, kind, source, stageId, budget));
    }

    @Override
    public AgentRun openInStageInCommand(
            String taskId, String kind, String source, String stageId, Integer budget)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Optional<AgentRun> existing = store.findLiveByTaskAndKind(taskId, kind);
        if (existing.isPresent()) {
            return existing.get();
        }
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source,
                stageId, /* reviewRoundId */ null, stageId, AgentRun.STATUS_RUNNING,
                /* iterations */ 0, budget, /* headline */ null, /* metricsJson */ null,
                now(), /* finishedAt */ null);
        return store.insert(run);
    }

    @Override
    public AgentRun openDetached(
            String kind, String source, String reviewRoundId, Integer budget)
    {
        requireText(kind, "kind");
        requireText(reviewRoundId, "reviewRoundId");
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), null, kind, source,
                null, reviewRoundId, null, AgentRun.STATUS_RUNNING,
                0, budget, null, null, now(), null);
        return store.insert(run);
    }

    @Override
    public AgentRun openTaskArtifact(
            String taskId, String kind, String source, String reviewRoundId, Integer budget)
    {
        requireText(taskId, "taskId");
        requireText(kind, "kind");
        requireText(reviewRoundId, "reviewRoundId");
        return commands.execute(taskId,
                () -> openTaskArtifactInCommand(taskId, kind, source, reviewRoundId, budget));
    }

    private AgentRun openTaskArtifactInCommand(
            String taskId, String kind, String source, String reviewRoundId, Integer budget)
    {
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source,
                null, reviewRoundId, null, AgentRun.STATUS_RUNNING,
                0, budget, null, null, now(), null);
        return store.insert(run);
    }

    @Override
    public AgentRun openSchedulerSession(
            Thread thread, String taskId, String stageId, String kind, String launchInput)
    {
        requireNonNull(thread, "thread is null");
        requireText(kind, "kind");
        requireText(launchInput, "launchInput");
        if (taskId != null && !taskId.isBlank()) {
            return commands.execute(taskId,
                    () -> openSchedulerSessionInCommand(
                            thread, taskId, stageId, kind, launchInput));
        }
        return openSchedulerSessionInCommand(thread, taskId, stageId, kind, launchInput);
    }

    private AgentRun openSchedulerSessionInCommand(
            Thread thread, String taskId, String stageId, String kind, String launchInput)
    {
        if (taskId != null && !taskId.isBlank()) {
            Optional<AgentRun> existing = store.findByTask(taskId, kind, stageId).stream()
                    .filter(run -> Objects.equals(stageId, run.stageId()))
                    .findFirst();
            if (existing.isPresent()) {
                AgentRun run = existing.get();
                if (!run.isLive()) {
                    AgentRun requeued = run.requeued();
                    run = store.transitionIf(
                            run.id(), run.status(), requeued.status(), requeued.finishedAt(),
                            requeued.pauseReason(), requeued.outcome())
                            ? requeued
                            : require(run.id());
                }
                if (run.workspaceId() == null || run.threadId() == null) {
                    AgentRun owned = run.withOwnership(
                            thread.workspaceId(), thread.id(), thread.provider(),
                            thread.model(), run.launchInput() == null ? launchInput : run.launchInput());
                    store.updateOwnership(
                            owned.id(), owned.workspaceId(), owned.threadId(), owned.provider(),
                            owned.model(), owned.launchInput());
                    run = owned;
                }
                return run;
            }
        }
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(),
                taskId,
                kind,
                AgentRun.SOURCE_SCHEDULED,
                stageId,
                null,
                stageId,
                AgentRun.STATUS_QUEUED,
                0,
                null,
                null,
                null,
                now(),
                null)
                .withOwnership(
                        thread.workspaceId(), thread.id(), thread.provider(),
                        thread.model(), launchInput);
        return store.insert(run);
    }

    @Override
    public AgentRun attachOwnership(
            String runId, String workspaceId, String threadId,
            String provider, String model, String launchInput)
    {
        requireText(runId, "runId");
        requireText(workspaceId, "workspaceId");
        requireText(launchInput, "launchInput");
        AgentRun updated = require(runId).withOwnership(
                workspaceId, threadId, provider, model, launchInput);
        store.updateOwnership(
                updated.id(), updated.workspaceId(), updated.threadId(), updated.provider(),
                updated.model(), updated.launchInput());
        return updated;
    }

    @Override
    public AgentRun recordIteration(String runId, String headlineOrNull)
    {
        AgentRun run = require(runId);
        AgentRun updated = run.withIteration(run.iterations() + 1, headlineOrNull);
        store.updateProgress(
                run.id(), updated.iterations(), updated.costUsdMilli(),
                updated.tokensIn(), updated.tokensOut());
        if (headlineOrNull != null) {
            store.updateHeadline(run.id(), updated.headline(), updated.outcome());
        }
        return updated;
    }

    @Override
    public AgentRun spendBudget(String runId)
    {
        AgentRun run = require(runId);
        AgentRun updated = run.withBudgetSpent();
        store.updateBudget(run.id(), updated.budget());
        return updated;
    }

    @Override
    public AgentRun updateHeadline(String runId, String headline)
    {
        AgentRun run = require(runId);
        AgentRun updated = run.withIteration(run.iterations(), headline);
        store.updateHeadline(run.id(), updated.headline(), updated.outcome());
        return updated;
    }

    @Override
    public AgentRun updateMetrics(String runId, String metricsJson)
    {
        AgentRun updated = require(runId).withMetrics(metricsJson);
        store.updateMetrics(runId, updated.metricsJson());
        return updated;
    }

    @Override
    public AgentRun updateAccounting(
            String runId, long costUsdMilli, long tokensIn, long tokensOut, int stepCursor)
    {
        AgentRun updated = require(runId).withAccounting(
                costUsdMilli, tokensIn, tokensOut, stepCursor);
        store.updateAccounting(
                runId, updated.costUsdMilli(), updated.tokensIn(), updated.tokensOut(),
                updated.stepCursor());
        return updated;
    }

    @Override
    public AgentRun pause(String runId, String reason)
    {
        AgentRun run = require(runId);
        if (run.taskId() != null && !run.taskId().isBlank()) {
            String taskId = run.taskId();
            return commands.execute(taskId,
                    () -> pauseInCommand(taskId, runId, reason));
        }
        return pause(run, reason);
    }

    @Override
    public AgentRun pauseInCommand(String taskId, String runId, String reason)
    {
        requireText(taskId, "taskId");
        TaskCommandExecutor.requireCurrent(taskId);
        AgentRun run = require(runId);
        if (!taskId.equals(run.taskId())) {
            throw new IllegalArgumentException(
                    "run " + runId + " does not belong to task " + taskId);
        }
        return pause(run, reason);
    }

    private AgentRun pause(AgentRun run, String reason)
    {
        if (!run.isLive()) {
            return run;
        }
        // Pause is idempotent. In particular, a coordinator reacting to a
        // budget-paused turn must not replace the actionable cap reason with
        // its internal lifecycle marker.
        if (AgentRun.STATUS_PAUSED.equals(run.status())) {
            return run;
        }
        AgentRun paused = run.paused(reason == null ? "paused by user" : reason);
        return store.transitionIf(
                run.id(), run.status(), paused.status(), paused.finishedAt(),
                paused.pauseReason(), paused.outcome())
                ? paused
                : require(run.id());
    }

    @Override
    public AgentRun resume(String runId)
    {
        AgentRun run = require(runId);
        if (run.taskId() != null && !run.taskId().isBlank()) {
            return commands.execute(run.taskId(), () -> resumeInCommand(run.taskId(), runId));
        }
        return resume(run);
    }

    private AgentRun resumeInCommand(String taskId, String runId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        AgentRun run = require(runId);
        if (!taskId.equals(run.taskId())) {
            throw new IllegalArgumentException(
                    "run " + runId + " does not belong to task " + taskId);
        }
        return resume(run);
    }

    private AgentRun resume(AgentRun run)
    {
        if (!AgentRun.STATUS_PAUSED.equals(run.status())) {
            return run;
        }
        AgentRun requeued = run.requeued();
        return store.transitionIf(
                run.id(), run.status(), requeued.status(), requeued.finishedAt(),
                requeued.pauseReason(), requeued.outcome())
                ? requeued
                : require(run.id());
    }

    @Override
    public AgentRun restart(String runId)
    {
        AgentRun prior = require(runId);
        if (prior.taskId() != null && !prior.taskId().isBlank()) {
            return commands.execute(prior.taskId(),
                    () -> restartInCommand(prior.taskId(), runId));
        }
        return restart(prior);
    }

    @Override
    public AgentRun restartInCommand(String taskId, String runId)
    {
        requireText(taskId, "taskId");
        TaskCommandExecutor.requireCurrent(taskId);
        AgentRun prior = require(runId);
        if (!taskId.equals(prior.taskId())) {
            throw new IllegalArgumentException(
                    "run " + runId + " does not belong to task " + taskId);
        }
        return restart(prior);
    }

    private AgentRun restart(AgentRun prior)
    {
        AgentRun restarted = new AgentRun(
                UUID.randomUUID().toString(),
                prior.taskId(),
                prior.kind(),
                prior.source(),
                prior.parentStageId(),
                prior.reviewRoundId(),
                prior.stageId(),
                AgentRun.STATUS_QUEUED,
                0,
                prior.budget(),
                prior.headline(),
                null,
                now(),
                null,
                prior.workspaceId(),
                prior.threadId(),
                prior.provider(),
                prior.model(),
                0L,
                0L,
                0L,
                0,
                prior.launchInput(),
                null,
                null);
        return store.insert(restarted);
    }

    @Override
    public AgentRun transition(String runId, String status, String reason)
    {
        AgentRun snapshot = require(runId);
        if (snapshot.taskId() != null && !snapshot.taskId().isBlank()) {
            return commands.execute(snapshot.taskId(),
                    () -> transitionCurrent(snapshot.taskId(), runId, status, reason));
        }
        return transitionCurrent(null, runId, status, reason);
    }

    @Override
    public AgentRun transitionInCommand(
            String taskId, String runId, String status, String reason)
    {
        requireText(taskId, "taskId");
        TaskCommandExecutor.requireCurrent(taskId);
        AgentRun run = require(runId);
        if (!taskId.equals(run.taskId())) {
            throw new IllegalArgumentException(
                    "run " + runId + " does not belong to task " + taskId);
        }
        return transitionCurrent(taskId, run, status, reason);
    }

    private AgentRun transitionCurrent(
            String taskId, String runId, String status, String reason)
    {
        return transitionCurrent(taskId, require(runId), status, reason);
    }

    private AgentRun transitionCurrent(
            String taskId, AgentRun run, String status, String reason)
    {
        if (!run.isLive()) {
            return run;
        }
        if (AgentRun.STATUS_PAUSED.equals(run.status())
                && !AgentRun.STATUS_CANCELLED.equals(status)
                && !AgentRun.STATUS_FAILED.equals(status)) {
            return run;
        }
        AgentRun updated = run.withStatus(status, now());
        if (!store.transitionIf(
                run.id(), run.status(), updated.status(), updated.finishedAt(),
                updated.pauseReason(), updated.outcome())) {
            return require(run.id());
        }
        if (updated.finishedAt() != null && updated.stageId() != null
                && !Objects.equals(updated.parentStageId(), updated.stageId())) {
            if (taskId == null) {
                throw new IllegalStateException(
                        "detached run " + run.id() + " cannot own a backing stage");
            }
            stages.closeInCommand(taskId, UUID.fromString(updated.stageId()), reason);
        }
        return updated;
    }

    private AgentRun require(String runId)
    {
        return store.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
