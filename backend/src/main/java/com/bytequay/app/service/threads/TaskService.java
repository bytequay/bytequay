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
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Service-layer surface for the work-unit Task — the branch + worktree
 * + PR row that belongs to a Thread. ThreadService still owns the
 * conversation lifecycle (create, send, pause, …); TaskService is the
 * boundary callers use when they want to know about the tasks inside
 * a thread, navigate them, or eventually ship-and-continue.
 *
 * <p>Reads are thin wrappers over {@link TaskStore}; the heavy
 * ship-and-continue logic (commit + push + open PR + cut next branch
 * + re-spawn the agent) lands as a follow-up.
 */
@Service
public class TaskService
{
    private final ThreadStore threadStore;
    private final TaskStore taskStore;

    public TaskService(ThreadStore threadStore, TaskStore taskStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    /** All tasks for a thread, ordered by seq ascending. 404 if the
     *  thread doesn't exist (so callers can't probe arbitrary ids). */
    public List<Task> listTasksForThread(String threadId)
    {
        requireThread(threadId);
        return taskStore.listTasksByThread(threadId);
    }

    /** Latest non-terminal task for the thread, or empty if the
     *  thread is in the 0-Task state (brainstorm / Q&A). */
    public Optional<Task> findActiveTask(String threadId)
    {
        requireThread(threadId);
        return taskStore.findActiveTaskForThread(threadId);
    }

    /** Single task lookup. 404s if the task is missing OR if it
     *  belongs to a different thread than the URL implies. */
    public Task requireTask(String threadId, String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (!task.threadId().equals(threadId)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "task " + taskId + " is not on thread " + threadId);
        }
        return task;
    }

    /** Per-(task, path) file rollup the agent has produced. Powers the
     *  "Files touched" sidebar at task scope. */
    public List<TaskFile> listFiles(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.listFiles(taskId);
    }

    private Thread requireThread(String threadId)
    {
        return threadStore.findThreadById(threadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no thread: " + threadId));
    }
}
