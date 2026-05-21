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
package com.bytequay.app.web;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.service.threads.TaskService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Read-only REST surface for the work-unit Task. Lives under each
 * thread because Tasks are sequenced inside a thread (seq 1..N) and
 * the URL hierarchy mirrors the model.
 *
 * <p>The work-unit endpoints that used to hang off the Thread
 * (working-changes, working-diff, commits, files) stay on
 * {@code ThreadController} for now so the unrenamed frontend keeps
 * working; once the frontend rename ships in Phase 4 those endpoints
 * either move here or get a parallel mount.
 */
@RestController
@RequestMapping("/api/threads/{threadId}/tasks")
public class TaskController
{
    private final TaskService taskService;

    public TaskController(TaskService taskService)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
    }

    /** All tasks for the thread, oldest seq first. The UI's left-rail
     *  "Tasks in this thread" list reads this. */
    @GetMapping
    public List<Task> list(@PathVariable String threadId)
    {
        return taskService.listTasksForThread(threadId);
    }

    /** The latest non-terminal task for this thread; 404 if the thread
     *  is in the 0-Task state. */
    @GetMapping("/active")
    public Task active(@PathVariable String threadId)
    {
        return taskService.findActiveTask(threadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no active task on thread: " + threadId));
    }

    /** Single task lookup, scoped to the parent thread. */
    @GetMapping("/{taskId}")
    public Task get(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.requireTask(threadId, taskId);
    }

    /** Files the agent has touched in this task's worktree. Returned
     *  most-recently-touched first. */
    @GetMapping("/{taskId}/files")
    public List<TaskFile> files(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.listFiles(threadId, taskId);
    }
}
