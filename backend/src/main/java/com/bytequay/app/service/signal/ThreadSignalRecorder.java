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
package com.bytequay.app.service.signal;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Bridges existing M8 lifecycle events into the passive thread-signal
 * feed. Additive — it only listens; it never alters the event source. A
 * signal is best-effort context, so any failure here is swallowed and
 * must never disturb the work that fired the event.
 */
@Component
public class ThreadSignalRecorder
{
    private static final Logger log = LoggerFactory.getLogger(ThreadSignalRecorder.class);

    private final ThreadSignalService signals;
    private final TaskStore taskStore;

    public ThreadSignalRecorder(ThreadSignalService signals, TaskStore taskStore)
    {
        this.signals = requireNonNull(signals, "signals is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    /** A new task on the thread becomes an informational signal. */
    @EventListener
    public void onTaskCreated(TaskCreatedEvent event)
    {
        try {
            Optional<Task> task = taskStore.findTaskById(event.taskId());
            if (task.isEmpty()) {
                return;
            }
            Task t = task.get();
            signals.record(
                    t.threadId(),
                    t.id(),
                    "system",
                    "info",
                    "Task " + t.seq() + " created",
                    null,
                    null);
        }
        catch (RuntimeException e) {
            log.debug("Failed to record task-created signal for {}", event.taskId(), e);
        }
    }
}
