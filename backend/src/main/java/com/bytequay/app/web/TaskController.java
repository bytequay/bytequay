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

import com.bytequay.app.beans.workmodel.ResolvedWorkModelResponse;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.inspector.AssembledContext;
import com.bytequay.app.service.inspector.ContextAssembler;
import com.bytequay.app.service.threads.TaskService;
import com.bytequay.app.service.workmodel.ScopeWorkModel;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ContextAssembler contextAssembler;
    private final WorkModelResolver workModelResolver;

    public TaskController(
            TaskService taskService,
            ContextAssembler contextAssembler,
            WorkModelResolver workModelResolver)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.contextAssembler = requireNonNull(contextAssembler, "contextAssembler is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
    }

    /** All tasks for the thread, oldest seq first. The UI's left-rail
     *  "Tasks in this thread" list reads this. */
    @GetMapping
    public List<Task> list(@PathVariable String threadId)
    {
        return taskService.listTasksForThread(threadId);
    }

    /**
     * GET /api/threads/{threadId}/tasks/{taskId}/context?dryRun=true
     * — read-only view of the task's prompt context. {@code dryRun=true}
     * is mandatory; the endpoint never dispatches. Same contract as
     * the trunk-scoped {@code /threads/{id}/context}.
     */
    @GetMapping("/{taskId}/context")
    public AssembledContext context(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestParam(value = "dryRun", required = false) Boolean dryRun)
    {
        if (!Boolean.TRUE.equals(dryRun)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "dryRun=true is required — this endpoint never dispatches");
        }
        return contextAssembler.forTask(threadId, taskId);
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

    /** Close a task: stop the agent, mark it CANCELED, and reap its
     *  worktree + branch. The user's explicit "throw this away". */
    @PostMapping("/{taskId}/cancel")
    public Task cancel(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.cancelTask(threadId, taskId);
    }

    /** Pause a task: stop the agent and park it at PAUSED, keeping the
     *  worktree + session so it can be resumed. The thread won't run a
     *  paused task, freeing the user to work on something else. */
    @PostMapping("/{taskId}/pause")
    public Task pause(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.pauseTask(threadId, taskId);
    }

    /** Resume a paused task back to IDLE so the thread runs it again. */
    @PostMapping("/{taskId}/resume")
    public Task resume(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.resumeTask(threadId, taskId);
    }

    /** Retry exhausted post-ship CI. Kept separate from Resume because this
     *  action causes the coordinator to rerun GitHub Actions. */
    @PostMapping("/{taskId}/retry-ci")
    public Task retryCi(@PathVariable String threadId, @PathVariable String taskId)
    {
        return taskService.retryFailedCi(threadId, taskId);
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

    /** GET the task's auto-approve mode. */
    @GetMapping("/{taskId}/auto-approve")
    public AutoApproveResponse getAutoApprove(
            @PathVariable String threadId,
            @PathVariable String taskId)
    {
        return new AutoApproveResponse(taskService.isAutoApprove(threadId, taskId));
    }

    /** Set the task's auto-approve mode. While on, the task's parked publish
     *  gates + in-turn tool prompts auto-approve; the final PR merge stays
     *  manually gated. */
    @PutMapping("/{taskId}/auto-approve")
    public AutoApproveResponse setAutoApprove(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody AutoApproveBody body)
    {
        boolean enabled = body != null && body.enabled();
        return new AutoApproveResponse(taskService.setAutoApprove(threadId, taskId, enabled));
    }

    /** Body for {@link #setAutoApprove}. */
    public record AutoApproveBody(boolean enabled)
    {
    }

    /** Response for the auto-approve endpoints. */
    public record AutoApproveResponse(boolean enabled)
    {
    }

    /** GET the task's auto-merge mode. */
    @GetMapping("/{taskId}/auto-merge")
    public AutoMergeResponse getAutoMerge(
            @PathVariable String threadId,
            @PathVariable String taskId)
    {
        return new AutoMergeResponse(taskService.isAutoMerge(threadId, taskId));
    }

    /** Set the task's auto-merge mode. Enabling it also turns on
     *  auto-approve, and is only allowed while the task's latest plan reads
     *  risk=low and effort=small (409 otherwise). While on, the final merge
     *  gate auto-approves too, on top of everything auto-approve already
     *  skips. */
    @PutMapping("/{taskId}/auto-merge")
    public AutoMergeResponse setAutoMerge(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody AutoMergeBody body)
    {
        boolean enabled = body != null && body.enabled();
        return new AutoMergeResponse(taskService.setAutoMerge(threadId, taskId, enabled));
    }

    /** Body for {@link #setAutoMerge}. */
    public record AutoMergeBody(boolean enabled)
    {
    }

    /** Response for the auto-merge endpoints. */
    public record AutoMergeResponse(boolean enabled)
    {
    }

    /** GET the task's minimum-approvals gate. */
    @GetMapping("/{taskId}/min-approvals")
    public MinApprovalsResponse getMinApprovals(
            @PathVariable String threadId,
            @PathVariable String taskId)
    {
        return new MinApprovalsResponse(taskService.getMinApprovals(threadId, taskId));
    }

    /** Set the task's minimum-approvals gate — the number of write-permission
     *  approvals a shipped PR needs before it counts as merge-ready. */
    @PutMapping("/{taskId}/min-approvals")
    public MinApprovalsResponse setMinApprovals(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody MinApprovalsBody body)
    {
        int value = body != null ? body.minApprovals() : 0;
        return new MinApprovalsResponse(taskService.setMinApprovals(threadId, taskId, value));
    }

    /** Body for {@link #setMinApprovals}. */
    public record MinApprovalsBody(int minApprovals)
    {
    }

    /** Response for the min-approvals endpoints. */
    public record MinApprovalsResponse(int minApprovals)
    {
    }

    /**
     * GET /api/threads/{threadId}/tasks/{taskId}/work-model — resolve the
     * effective work model for a task, returning both the scope's own
     * override (nullable) and the cascade winner with provenance.
     */
    @GetMapping("/{taskId}/work-model")
    public ResolvedWorkModelResponse getWorkModel(
            @PathVariable String threadId,
            @PathVariable String taskId)
    {
        Task task = taskService.requireTask(threadId, taskId);
        WorkModelResolver.Resolved resolved = workModelResolver.resolveForTask(threadId, taskId);
        return new ResolvedWorkModelResponse(
                task.workModel(), resolved.choice(), resolved.provenance(),
                taskService.isWorkModelAgentLocked(threadId, taskId));
    }

    /**
     * PUT /api/threads/{threadId}/tasks/{taskId}/work-model — set (or
     * clear) the task's reasoning-effort override. The engine is the
     * workspace's call, so engine fields in the body are ignored; a body
     * with no effort clears the override and the thread's (then the
     * workspace's) effort applies. Returns the resolved outcome so the
     * caller does not need a follow-up GET.
     */
    @PutMapping("/{taskId}/work-model")
    public ResolvedWorkModelResponse setWorkModel(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody(required = false) WorkModelBody body)
    {
        WorkModel requested = body == null ? null : body.workModel();
        Task updated = taskService.setWorkModel(threadId, taskId, ScopeWorkModel.effortOnly(
                workModelResolver.resolveForTask(threadId, taskId).choice(), requested));
        WorkModelResolver.Resolved resolved = workModelResolver.resolveForTask(threadId, taskId);
        return new ResolvedWorkModelResponse(
                updated.workModel(), resolved.choice(), resolved.provenance(),
                taskService.isWorkModelAgentLocked(threadId, taskId));
    }

    /** Body for {@link #setWorkModel} — wraps the optional
     *  {@link WorkModel} so a {@code null} field maps cleanly to
     *  "clear the override". */
    public record WorkModelBody(WorkModel workModel) {}

    /**
     * PUT /api/threads/{threadId}/tasks/{taskId}/opening-prompt — append
     * to (or replace) a QUEUED task's opening-prompt accumulator. The
     * composer on a queued task page writes here; when the slot opens the
     * accumulated text becomes the agent's first turn. 422 when the task
     * isn't QUEUED — the plan seals once it starts.
     */
    @PutMapping("/{taskId}/opening-prompt")
    public Task setOpeningPrompt(
            @PathVariable String threadId,
            @PathVariable String taskId,
            @RequestBody OpeningPromptBody body)
    {
        // Scope the task to the thread before mutating, same as the other
        // verbs here (404 on a mismatch).
        taskService.requireTask(threadId, taskId);
        boolean append = body == null || body.mode() == null || !"replace".equalsIgnoreCase(body.mode());
        String text = body == null ? "" : body.text();
        return taskService.updateOpeningPrompt(taskId, text, append);
    }

    /** Body for {@link #setOpeningPrompt} — {@code mode} is 'append'
     *  (default) or 'replace'. */
    public record OpeningPromptBody(String text, String mode)
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
