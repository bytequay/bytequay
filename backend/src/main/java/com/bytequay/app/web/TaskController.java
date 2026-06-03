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
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.threads.TaskService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Close out the current task (commit + push + open PR) and start
     *  the next one inside the same thread. See
     *  {@link TaskService#shipAndContinue} for the full flow. */
    @Concept(
            name = "ship",
            kind = ConceptKind.VERB,
            definition = "Finalise the current task — commit, push, open a PR — and start "
                    + "the next task inside the same thread. The end-of-work transition; "
                    + "the foreground task moves out of the way and a fresh one cut from "
                    + "main becomes foreground.",
            examples = "ship after request_review is approved and the user wants to publish.",
            relatedConcepts = {"task", "next", "awaiting_review"})
    @PostMapping("/{taskId}/ship")
    public Task ship(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody(required = false) TaskService.ShipRequest body)
    {
        TaskService.ShipRequest request = body != null
                ? body
                : new TaskService.ShipRequest(null, TaskService.BaseMode.MAIN);
        return taskService.shipAndContinue(threadId, taskId, request);
    }

    /** Rename a task. Trimmed; an empty / null body clears the
     *  rename and reverts to the humanised branch-derived label. */
    @PatchMapping("/{taskId}/name")
    public Task rename(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody RenameBody body)
    {
        return taskService.renameTask(threadId, taskId, body == null ? null : body.name());
    }

    /** Body for {@link #rename}. */
    public record RenameBody(String name)
    {
    }

    /** Next → park the current task at AWAITING_REVIEW (worktree
     *  preserved) and start a fresh task cut from main. The trunk
     *  window's Next button calls this. See
     *  {@link TaskService#parkAndStartNext}. */
    @Concept(
            name = "next",
            kind = ConceptKind.VERB,
            definition = "Park the current task at AWAITING_REVIEW (worktree preserved) "
                    + "and start a fresh task cut from main. The mid-flight transition "
                    + "for when one unit of work is done enough to set aside and the "
                    + "user wants to start the next one without losing context.",
            examples = "next when the agent's diff is ready to review and the user wants "
                    + "to start the follow-up without waiting for sign-off.",
            relatedConcepts = {"task", "ship", "awaiting_review"})
    @PostMapping("/{taskId}/next")
    public Task next(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody(required = false) TaskService.ShipRequest body)
    {
        TaskService.ShipRequest request = body != null
                ? body
                : new TaskService.ShipRequest(null, TaskService.BaseMode.MAIN);
        return taskService.parkAndStartNext(threadId, taskId, request);
    }
}
