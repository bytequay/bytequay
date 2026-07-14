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
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.StageStore;
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
    private final StageStore stageStore;
    private final Clock clock;

    @Autowired
    AgentRunServiceImpl(AgentRunStore store, StageStore stageStore)
    {
        this(store, stageStore, Clock.systemUTC());
    }

    AgentRunServiceImpl(AgentRunStore store, StageStore stageStore, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public Optional<AgentRun> findById(String runId)
    {
        return store.findById(runId);
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
        Optional<AgentRun> existing = store.findLiveByTaskAndKind(taskId, kind);
        if (existing.isPresent()) {
            return existing.get();
        }
        // A closed stage of this type means the task already ran this kind of
        // work before (an earlier CI-fix attempt, review round, guard tick) —
        // wake it back up instead of opening a second one, so whatever agent
        // session is cached under its id gets reused rather than rebuilt from
        // scratch.
        StageInstance backing = stageStore.findStageByType(taskId, backingStageType)
                .map(found -> found.state() == StageState.CLOSED
                        ? stageStore.reopenStage(found.id())
                        : found)
                .orElseGet(() -> stageStore.openStage(taskId, backingStageType, null));
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source, parentStageId,
                /* reviewRoundId */ null, backing.id().toString(), AgentRun.STATUS_RUNNING,
                /* iterations */ 0, budget, /* headline */ null, /* metricsJson */ null,
                now(), /* finishedAt */ null);
        return store.save(run);
    }

    @Override
    public AgentRun openInStage(
            String taskId, String kind, String source, String stageId, Integer budget)
    {
        requireText(taskId, "taskId");
        requireText(kind, "kind");
        requireText(stageId, "stageId");
        Optional<AgentRun> existing = store.findLiveByTaskAndKind(taskId, kind);
        if (existing.isPresent()) {
            return existing.get();
        }
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source,
                stageId, /* reviewRoundId */ null, stageId, AgentRun.STATUS_RUNNING,
                /* iterations */ 0, budget, /* headline */ null, /* metricsJson */ null,
                now(), /* finishedAt */ null);
        return store.save(run);
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
        return store.save(run);
    }

    @Override
    public AgentRun openTaskArtifact(
            String taskId, String kind, String source, String reviewRoundId, Integer budget)
    {
        requireText(taskId, "taskId");
        requireText(kind, "kind");
        requireText(reviewRoundId, "reviewRoundId");
        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(), taskId, kind, source,
                null, reviewRoundId, null, AgentRun.STATUS_RUNNING,
                0, budget, null, null, now(), null);
        return store.save(run);
    }

    @Override
    public AgentRun recordIteration(String runId, String headlineOrNull)
    {
        AgentRun run = require(runId);
        return store.save(run.withIteration(run.iterations() + 1, headlineOrNull));
    }

    @Override
    public AgentRun spendBudget(String runId)
    {
        AgentRun run = require(runId);
        return store.save(run.withBudgetSpent());
    }

    @Override
    public AgentRun updateHeadline(String runId, String headline)
    {
        AgentRun run = require(runId);
        return store.save(run.withIteration(run.iterations(), headline));
    }

    @Override
    public AgentRun updateMetrics(String runId, String metricsJson)
    {
        return store.save(require(runId).withMetrics(metricsJson));
    }

    @Override
    public synchronized AgentRun transition(String runId, String status, String reason)
    {
        AgentRun run = require(runId);
        if (!run.isLive()) {
            return run;
        }
        AgentRun updated = store.save(run.withStatus(status, now()));
        if (updated.finishedAt() != null && updated.stageId() != null
                && !Objects.equals(updated.parentStageId(), updated.stageId())) {
            stageStore.closeStage(UUID.fromString(updated.stageId()), reason);
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
