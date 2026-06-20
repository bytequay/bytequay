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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The Task-level write mutex that serialises the two monitor loops'
 * write phases on the shared branch. A coordination primitive, not a
 * synchronous held lock: a monitor stage's driver tries to acquire it
 * before enqueuing a write turn and <em>skips this cycle</em> (skip-don't-
 * wait) if another stage holds it; the lock is released when that stage's
 * turn finishes.
 *
 * <p>Acquire/release are atomic compare-and-set queries on the task row,
 * so two concurrent polls can never both win. Every acquire/skip writes
 * a {@code MUTEX_ACQUIRED} / {@code MUTEX_SKIPPED} stage event for the
 * audit trail. {@link #onTurnFinished} is the safety release: it clears
 * the lock on any task turn's completion so a crash mid-sequence can't
 * leave a stage starved forever.
 */
@Component
public class TaskWriteMutex
{
    private final TaskStore taskStore;
    private final StageStore stageStore;

    public TaskWriteMutex(TaskStore taskStore, StageStore stageStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    /**
     * Try to take the write lock for {@code stageId}. Returns true on
     * success (writing {@code MUTEX_ACQUIRED}); false when another stage
     * already holds it (writing {@code MUTEX_SKIPPED} with the holder).
     */
    @Transactional
    public boolean tryAcquire(String taskId, UUID stageId)
    {
        if (taskStore.tryAcquireWriteMutex(taskId, stageId.toString())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("previousHolder", null);
            stageStore.recordEvent(stageId, taskId, StageEventType.MUTEX_ACQUIRED, payload);
            return true;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("holder", taskStore.writeMutexHolder(taskId).orElse(null));
        payload.put("skipReason", "mutex_held");
        stageStore.recordEvent(stageId, taskId, StageEventType.MUTEX_SKIPPED, payload);
        return false;
    }

    /** Release the lock iff {@code stageId} holds it. */
    @Transactional
    public void release(String taskId, UUID stageId)
    {
        taskStore.releaseWriteMutex(taskId, stageId.toString());
    }

    /** Safety release: clear the lock whenever a task's turn finishes, so
     *  a held lock can never outlive the turn that took it. No-op when the
     *  task held nothing. */
    @EventListener
    @Transactional
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        taskStore.releaseWriteMutexForTask(event.taskId());
    }
}
