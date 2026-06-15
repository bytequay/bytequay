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

import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Turns a PENDING queue head into a real {@link Task} row in the
 * {@link TaskPhase#QUEUED} phase — eager materialisation, so the user
 * gets a real task page + URL while the work waits for a compute slot.
 *
 * <p>The seam between the pure queue mutator ({@link TaskQueueService})
 * and the task lifecycle ({@link ThreadService#materialiseTask}, which
 * cuts the worktree). Both the {@code create_task} tool (bootstrap /
 * dry-chain revival) and the advance-on-complete scheduler hook drive
 * materialisation through here so the steps stay identical.
 *
 * <p>The materialised task is <em>not</em> started here: it lands at
 * {@code phase = QUEUED} with the queue entry's opening prompt seeded
 * onto {@code opening_prompt}. The scheduler promotes
 * {@code QUEUED → IMPLEMENTING} and feeds that prompt as the first turn
 * once a slot is free.
 */
@Service
public class TaskQueueMaterialiser
{
    private final ThreadService threadService;
    private final TaskStore taskStore;
    private final TaskQueueService queue;

    public TaskQueueMaterialiser(
            ThreadService threadService, TaskStore taskStore, TaskQueueService queue)
    {
        this.threadService = requireNonNull(threadService, "threadService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.queue = requireNonNull(queue, "queue is null");
    }

    /** Convenience passthrough so callers need only this one dependency. */
    public Optional<QueuedTask> pendingHead(Thread thread)
    {
        return queue.pendingHead(thread);
    }

    /**
     * Materialise {@code head} into a QUEUED task cut from {@code
     * workingDir} (the repo clone). Sets the opening prompt, flips the
     * entry to MATERIALIZED with the new task id, and returns the task.
     *
     * <p>The branch is cut from the repo's default branch regardless of
     * {@link com.bytequay.app.domain.BranchBase}: stacked-on-previous is
     * resolved by the advance scheduler (which knows the prior slice's
     * branch), not on this bootstrap path where there is no live
     * predecessor.
     */
    @Transactional
    public Task materialiseHead(Thread thread, QueuedTask head, String workingDir)
    {
        requireNonNull(thread, "thread is null");
        requireNonNull(head, "head is null");
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                thread.kind(),
                thread.provider(),
                thread.model(),
                head.title(),
                workingDir,
                /* branchName — worktree create derives it */ null,
                /* initialPrompt — deferred to opening_prompt + slot open */ null,
                /* initialGroupIds */ List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                thread.flow(),
                thread.workspaceId(),
                thread.workModel());
        Task task = threadService.materialiseTask(thread.id(), request);
        taskStore.updatePhase(task.id(), TaskPhase.QUEUED);
        if (head.initialPrompt() != null && !head.initialPrompt().isBlank()) {
            taskStore.setOpeningPrompt(task.id(), head.initialPrompt());
        }
        queue.markMaterialized(thread.id(), head.position(), task.id());
        return taskStore.findTaskById(task.id()).orElse(task);
    }
}
