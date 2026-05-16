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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Cleans up tasks the previous backend process left running. Any
 * {@link TaskStatus#RUNNING} row at startup is by definition orphaned
 * — its subprocess is gone — so we flip it to {@link TaskStatus#IDLE}.
 * The user can resume by sending a follow-up turn, which spawns a
 * fresh {@code claude --resume <session-id>}.
 *
 * <p>Other non-terminal statuses ({@code PENDING}, {@code AWAITING},
 * {@code IDLE}) are already correct; sessions get re-created lazily
 * by {@link TaskSessionRegistry#getOrCreate} on first hit.
 */
@Component
public class TaskStartupReconciler
{
    private static final Logger log = LoggerFactory.getLogger(TaskStartupReconciler.class);

    /** Effectively unlimited — we want every orphaned RUNNING row,
     *  not a paged subset. Tasks tables don't reach the millions even
     *  for power users. */
    private static final int SCAN_LIMIT = 10_000;

    private final TaskStore store;

    public TaskStartupReconciler(TaskStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup()
    {
        List<Task> orphaned = store.listTasksByStatus(TaskStatus.RUNNING, SCAN_LIMIT);
        if (orphaned.isEmpty()) {
            return;
        }
        log.info("Reconciling {} orphaned RUNNING task(s) to IDLE", orphaned.size());
        Instant now = Instant.now();
        for (Task task : orphaned) {
            store.saveTask(new Task(
                    task.id(), task.kind(), task.provider(), task.agentSessionId(),
                    task.title(), TaskStatus.IDLE, task.workingDir(), task.branchName(),
                    task.model(),
                    task.costUsdMilli(), task.tokensIn(), task.tokensOut(),
                    /* processPid */ null, task.logPath(),
                    task.createdAt(), now,
                    task.endedAt(), task.errorMessage(), task.metadataJson(),
                    task.groupId()));
        }
    }
}
