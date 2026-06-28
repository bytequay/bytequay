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

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.stage.StageClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final TaskStore taskStore;

    public StageAgentReaper(ThreadRegistry registry, TaskStore taskStore)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
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
        String threadId = taskStore.findTaskById(event.taskId())
                .map(Task::threadId)
                .orElse(null);
        // Stop the subprocess before evicting so a mid-flight CLI exits at
        // its next tool boundary; evictStage then drops the session and
        // releases its worktree lease. Best-effort — a close must never
        // throw back into the phase machine's listeners.
        try {
            registry.findStage(stageId).ifPresent(agent -> {
                try {
                    agent.stop();
                }
                catch (RuntimeException e) {
                    log.warn("stop of stage agent {} threw: {}", stageId, e.getMessage());
                }
            });
            registry.evictStage(threadId, stageId);
        }
        catch (RuntimeException e) {
            log.warn("reaping stage agent {} (task {}) threw: {}",
                    stageId, event.taskId(), e.getMessage());
        }
    }
}
