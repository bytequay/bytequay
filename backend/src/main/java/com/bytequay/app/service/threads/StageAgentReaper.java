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
package com.bytequay.app.service.threads;

import com.bytequay.app.config.AsyncConfig;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.stage.StageClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/**
 * Reaps the per-stage CLI agent when its stage closes. Each stage of a
 * task (Development, CI-fixing, Comments-addressing) runs its own fresh
 * agent keyed by stage id; once the stage closes that agent is done, so
 * we stop its subprocess and drop it from the {@link ThreadRegistry},
 * which also releases the worktree lease the session held. The thread's
 * other concurrent stages are untouched.
 *
 * <p>Runs after the phase-transition transaction commits so we never reap
 * an agent for a stage close that rolled back.
 */
@Component
public class StageAgentReaper
{
    private static final Logger log = LoggerFactory.getLogger(StageAgentReaper.class);

    private final ThreadRegistry registry;
    private final StageStore stageStore;
    private final Executor executor;

    public StageAgentReaper(
            ThreadRegistry registry,
            StageStore stageStore,
            @Qualifier(AsyncConfig.APPLICATION_EXECUTOR) Executor executor)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onStageClosed(StageClosedEvent event)
    {
        String stageId = event.stageId();
        if (stageId == null || stageId.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> reapStage(stageId, event.taskId()));
        }
        catch (RuntimeException e) {
            // The scheduled sweep is the durable delivery guarantee.
            log.warn("submitting stage-agent reap {} (task {}) threw: {}",
                    stageId, event.taskId(), e.getMessage());
        }
    }

    /** Startup and scheduled backstop for a dropped callback or executor
     *  rejection. A deleted stage is just as terminal for its cached agent
     *  as a CLOSED row. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reconcileClosedStages()
    {
        for (String stageId : registry.cachedStageIds()) {
            UUID id;
            try {
                id = UUID.fromString(stageId);
            }
            catch (IllegalArgumentException e) {
                // A legacy non-UUID active-stage key cannot refer to a
                // durable task_stage row, so it is stale and must be reaped.
                reapStage(stageId, null);
                continue;
            }
            var stage = stageStore.findStageById(id);
            if (stage.isEmpty() || stage.orElseThrow().state() == StageState.CLOSED) {
                reapStage(stageId, stage.map(s -> s.taskId()).orElse(null));
            }
        }
    }

    /** The one idempotent runtime teardown used by callback and sweep. */
    private void reapStage(String stageId, String taskId)
    {
        try {
            registry.findStages(List.of(stageId)).forEach(agent -> {
                try {
                    agent.stop();
                }
                catch (RuntimeException e) {
                    log.warn("stop of stage agent {} threw: {}", stageId, e.getMessage());
                }
            });
            registry.evictStage(null, stageId);
        }
        catch (RuntimeException e) {
            log.warn("reaping stage agent {} (task {}) threw: {}",
                    stageId, taskId, e.getMessage());
        }
    }
}
