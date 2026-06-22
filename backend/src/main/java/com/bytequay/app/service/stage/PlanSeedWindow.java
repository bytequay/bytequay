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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The trunk's seed conversation for a task — the messages on the task's
 * (trunk) thread from the previous task's creation up to this task's cut.
 * That discussion is what led the trunk to cut the task; it's copied onto
 * the task's brain thread as the first messages of the plan stage, so the
 * brain agent, the brain feed, and the development agent all read it from
 * the brain thread alone (single source — no on-the-fly cross-thread reads).
 */
public final class PlanSeedWindow
{
    private PlanSeedWindow() {}

    /** Creation time of the most recent task on this thread created before
     *  {@code task} — the lower bound of its seed window. {@link Instant#MIN}
     *  when this is the thread's first task or createdAt is unknown. */
    public static Instant previousTaskBoundary(TaskStore tasks, Task task)
    {
        Instant self = task.createdAt();
        if (self == null) {
            return Instant.MIN;
        }
        return tasks.listTasksByThread(task.threadId()).stream()
                .filter(t -> !t.id().equals(task.id()))
                .map(Task::createdAt)
                .filter(c -> c != null && c.isBefore(self))
                .max(Comparator.naturalOrder())
                .orElse(Instant.MIN);
    }

    /** The trunk thread's user + assistant text messages from the previous
     *  task boundary up to (and including) this task's cut, oldest-first. */
    public static List<ThreadMessage> trunkSeedMessages(TaskStore tasks, ThreadStore threads, Task task)
    {
        if (task.threadId() == null) {
            return List.of();
        }
        Instant from = previousTaskBoundary(tasks, task);
        Instant to = task.createdAt();
        return threads.listMessages(task.threadId()).stream()
                .filter(m -> "text".equals(m.type()))
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .filter(m -> !m.ts().isBefore(from))
                .filter(m -> to == null || !m.ts().isAfter(to))
                .sorted(Comparator.comparingLong(ThreadMessage::seq))
                .toList();
    }
}
