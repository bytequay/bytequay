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

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.tasks.TaskService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Local REST surface for the Tasks page.
 *
 * <p>Exposes one resource per primitive in {@link TaskService}:
 * list/create on {@code /api/tasks}, detail/history under
 * {@code /api/tasks/{id}}, mutating verbs as POST sub-paths, and a
 * single SSE stream that the frontend's task-detail view subscribes
 * to for live events.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController
{
    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    /** Per-page cap; matches the design doc's "show ~50 most recent
     *  tasks" target for the list view. */
    private static final int DEFAULT_LIMIT = 50;

    /** Six hours — long enough for an unattended overnight run, short
     *  enough that abandoned browser tabs don't leak the stream. */
    private static final long STREAM_TIMEOUT_MS = 6L * 60L * 60L * 1000L;

    private final TaskService tasks;

    public TaskController(TaskService tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    /** GET /api/tasks?status=RUNNING&limit=50&groupId=... */
    @GetMapping
    public List<Task> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit)
    {
        int cap = Math.min(limit, DEFAULT_LIMIT);
        if (groupId != null && !groupId.isBlank()) {
            List<Task> page = tasks.listByGroup(groupId, cap);
            if (status == null) {
                return page;
            }
            return page.stream().filter(t -> t.status() == status).toList();
        }
        if (status == null) {
            ImmutableList.Builder<Task> all = ImmutableList.builder();
            for (TaskStatus s : TaskStatus.values()) {
                all.addAll(tasks.listByStatus(s, cap));
            }
            return all.build();
        }
        return tasks.listByStatus(status, cap);
    }

    /** POST /api/tasks — create + start. Returns the persisted row. */
    @PostMapping
    public Task create(@RequestBody NewTaskBody body)
    {
        requireNonNull(body, "body is null");
        if (body.kind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (body.workingDir() == null || body.workingDir().isBlank()) {
            throw new IllegalArgumentException("workingDir is required");
        }
        if (body.title() == null || body.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        return tasks.create(new TaskService.NewTaskRequest(
                body.kind(),
                body.provider() == null ? "claude-code" : body.provider(),
                body.model(),
                body.title(),
                body.workingDir(),
                body.branchName(),
                body.initialPrompt(),
                body.metadataJson(),
                body.initialGroupIds() == null ? List.of() : body.initialGroupIds()));
    }

    /** GET /api/tasks/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable String id)
    {
        return tasks.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * PATCH /api/tasks/{id} — partial update. Only {@code title} is
     * editable; pass a non-null, non-blank string to rename. Group
     * membership moved to its own endpoints under
     * {@code /api/task-groups/{id}/members/{taskId}} since one task
     * can belong to many groups.
     */
    @PatchMapping("/{id}")
    public Task patch(@PathVariable String id, @RequestBody PatchTaskBody body)
    {
        requireNonNull(body, "body is required");
        return tasks.patchTask(id, new TaskService.TaskPatch(body.title()));
    }

    /** GET /api/tasks/{id}/messages — full conversation, oldest first. */
    @GetMapping("/{id}/messages")
    public List<TaskMessage> messages(@PathVariable String id)
    {
        return tasks.history(id);
    }

    /** GET /api/tasks/{id}/files — per-file rollup, most-recently
     *  touched first. Powers the sidebar's Files touched card. */
    @GetMapping("/{id}/files")
    public List<TaskFile> files(@PathVariable String id)
    {
        return tasks.files(id);
    }

    // ── Tabs: Files (uncommitted) + Commits (since task started) ─────

    /** GET /api/tasks/{id}/working-changes — paths the AI session has
     *  modified but not yet committed. Drives the Files tab. */
    @GetMapping("/{id}/working-changes")
    public List<GitRunner.WorkingTreeFile> workingChanges(@PathVariable String id)
    {
        return tasks.listWorkingChanges(id);
    }

    /** GET /api/tasks/{id}/working-diff?path=... — unified diff for
     *  one uncommitted file. Capped at 256 KB. */
    @GetMapping("/{id}/working-diff")
    public Map<String, String> workingDiff(
            @PathVariable String id,
            @RequestParam String path)
    {
        return ImmutableMap.of("diff", tasks.getWorkingDiff(id, path));
    }

    /** GET /api/tasks/{id}/commits — commits authored since the task
     *  was created. Drives the Commits tab. */
    @GetMapping("/{id}/commits")
    public List<GitRunner.CommitEntry> commits(@PathVariable String id)
    {
        return tasks.listTaskCommits(id);
    }

    /** GET /api/tasks/{id}/commits/{sha}/files — per-file rollup for
     *  one of the task's commits. */
    @GetMapping("/{id}/commits/{sha}/files")
    public List<GitRunner.CommitFileChange> commitFiles(
            @PathVariable String id,
            @PathVariable String sha)
    {
        return tasks.listCommitFiles(id, sha);
    }

    /** GET /api/tasks/{id}/commits/{sha}/diff?path=... — unified diff
     *  for one file at one commit. */
    @GetMapping("/{id}/commits/{sha}/diff")
    public Map<String, String> commitDiff(
            @PathVariable String id,
            @PathVariable String sha,
            @RequestParam String path)
    {
        return ImmutableMap.of("diff", tasks.getCommitDiff(id, sha, path));
    }

    /** POST /api/tasks/{id}/messages — send a follow-up turn. */
    @PostMapping("/{id}/messages")
    public Map<String, String> send(@PathVariable String id, @RequestBody SendBody body)
    {
        requireNonNull(body, "body is null");
        if (body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input is required");
        }
        tasks.send(id, body.input());
        return ImmutableMap.of("status", "queued");
    }

    @PostMapping("/{id}/interrupt")
    public Map<String, String> interrupt(@PathVariable String id)
    {
        tasks.interrupt(id);
        return ImmutableMap.of("status", "interrupted");
    }

    @PostMapping("/{id}/pause")
    public Map<String, String> pause(@PathVariable String id)
    {
        tasks.pause(id);
        return ImmutableMap.of("status", "paused");
    }

    @PostMapping("/{id}/resume")
    public Map<String, String> resume(@PathVariable String id)
    {
        tasks.resume(id);
        return ImmutableMap.of("status", "resumed");
    }

    @PostMapping("/{id}/stop")
    public Map<String, String> stop(@PathVariable String id)
    {
        tasks.stop(id);
        return ImmutableMap.of("status", "stopped");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id)
    {
        tasks.delete(id);
        return ImmutableMap.of("status", "deleted");
    }

    @PostMapping("/{id}/decisions")
    public Map<String, String> decide(@PathVariable String id, @RequestBody DecisionBody body)
    {
        requireNonNull(body, "body is null");
        if (body.callId() == null || body.callId().isBlank()) {
            throw new IllegalArgumentException("callId is required");
        }
        if (body.decision() == null) {
            throw new IllegalArgumentException("decision is required");
        }
        tasks.decide(id, body.callId(), body.decision());
        // Optional pre-approval rider — when the user clicks "Allow next
        // 5 / 10 / 50 / Always" we record the current decision first,
        // then grant the budget so subsequent calls of the same tool
        // skip the prompt. -1 is the "always" sentinel.
        if (body.preApproveToolName() != null
                && !body.preApproveToolName().isBlank()
                && body.preApproveCount() != null
                && body.preApproveCount() != 0) {
            tasks.grantToolBudget(id, body.preApproveToolName(), body.preApproveCount());
        }
        return ImmutableMap.of("status", "recorded");
    }

    /**
     * GET /api/tasks/{id}/stream — Server-Sent Events of every
     * {@link com.bytequay.app.domain.StreamEvent} the session emits
     * after subscription. The renderer uses the {@code event:}
     * channel to switch on shape; all payloads are JSON.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id)
    {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Runnable unsubscribe = tasks.subscribe(id, event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getClass().getSimpleName())
                        .data(event));
            }
            catch (IOException e) {
                // Client gone — surface so the upstream cleanup runs.
                throw new IllegalStateException("SSE channel closed", e);
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(() -> {
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(t -> {
            log.debug("SSE error on task {} stream: {}", id, t.getMessage());
            unsubscribe.run();
        });
        return emitter;
    }

    /** POST body for {@link #create}. */
    public record NewTaskBody(
            TaskKind kind,
            String provider,
            String model,
            String title,
            String workingDir,
            String branchName,
            String initialPrompt,
            String metadataJson,
            /** Optional — pre-pin the new task into one or more existing
             *  groups. Each must have room (cap is enforced server-side). */
            List<String> initialGroupIds) {}

    public record SendBody(String input) {}

    public record DecisionBody(
            String callId,
            PermissionDecision decision,
            String preApproveToolName,
            Integer preApproveCount) {}

    public record PatchTaskBody(String title) {}
}
