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

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskGroupStore;
import com.bytequay.app.repository.TaskStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Top-level facade controllers call into. Hides the registry / store
 * split: every endpoint either persists a row, mutates a session, or
 * subscribes to events — TaskService offers exactly those verbs.
 */
@Service
public class TaskService
{
    private final TaskStore store;
    private final TaskGroupStore groupStore;
    private final TaskSessionRegistry registry;

    public TaskService(TaskStore store, TaskGroupStore groupStore, TaskSessionRegistry registry)
    {
        this.store = requireNonNull(store, "store is null");
        this.groupStore = requireNonNull(groupStore, "groupStore is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    public List<Task> listByStatus(TaskStatus status, int limit)
    {
        return store.listTasksByStatus(status, limit);
    }

    public List<Task> listByGroup(String groupId, int limit)
    {
        return store.listTasksByGroup(groupId, limit);
    }

    public List<TaskGroup> listGroups()
    {
        return groupStore.listGroups();
    }

    public TaskGroup createGroup(NewGroupRequest request)
    {
        requireNonNull(request, "request is null");
        Instant now = Instant.now();
        TaskGroup group = new TaskGroup(
                UUID.randomUUID().toString(),
                request.name(),
                request.glyph() == null || request.glyph().isBlank() ? "•" : request.glyph(),
                request.color() == null || request.color().isBlank() ? "slate" : request.color(),
                request.sortOrder(),
                now,
                now);
        groupStore.saveGroup(group);
        return groupStore.findGroupById(group.id()).orElse(group);
    }

    /** Partial update — only non-null fields on {@code patch} change.
     *  Mirrors the Group settings panel: edit any of name/glyph/color
     *  independently. */
    public TaskGroup updateGroup(String groupId, GroupPatch patch)
    {
        requireNonNull(groupId, "groupId is null");
        requireNonNull(patch, "patch is null");
        TaskGroup current = groupStore.findGroupById(groupId)
                .orElseThrow(() -> new NoSuchElementException("no group: " + groupId));
        TaskGroup next = new TaskGroup(
                current.id(),
                patch.name() != null && !patch.name().isBlank() ? patch.name() : current.name(),
                patch.glyph() != null && !patch.glyph().isBlank() ? patch.glyph() : current.glyph(),
                patch.color() != null && !patch.color().isBlank() ? patch.color() : current.color(),
                current.sortOrder(),
                current.createdAt(),
                Instant.now());
        groupStore.saveGroup(next);
        return next;
    }

    /** Tasks pointing at this group keep existing — their
     *  {@code group_id} is cleared so they survive the deletion. */
    public void deleteGroup(String groupId)
    {
        store.unsetGroupOnTasks(groupId);
        groupStore.deleteGroup(groupId);
    }

    /**
     * Partial update — only fields the caller wants to change. Title
     * accepts a trimmed non-blank string; group accepts the sentinel
     * {@link TaskPatch#clearGroup} via the {@code group} carrier to
     * unset a pin (a plain {@code null} field means "don't change").
     *
     * <p>Validates the target group exists when set so the UI can't
     * strand a task on a stale dropdown selection.
     */
    public Task patchTask(String taskId, TaskPatch patch)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(patch, "patch is null");
        Task current = store.findTaskById(taskId)
                .orElseThrow(() -> new NoSuchElementException("no task: " + taskId));

        String nextTitle = current.title();
        if (patch.title() != null && !patch.title().isBlank()) {
            nextTitle = patch.title().trim();
        }

        String nextGroupId = current.groupId();
        if (patch.group() != null) {
            String pick = patch.group().value();
            if (pick != null && groupStore.findGroupById(pick).isEmpty()) {
                throw new NoSuchElementException("no group: " + pick);
            }
            nextGroupId = pick;
        }

        Task next = new Task(
                current.id(), current.kind(), current.provider(), current.agentSessionId(),
                nextTitle, current.status(), current.workingDir(), current.branchName(),
                current.model(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.processPid(), current.logPath(),
                current.createdAt(), Instant.now(),
                current.endedAt(), current.errorMessage(), current.metadataJson(),
                nextGroupId);
        store.saveTask(next);
        return store.findTaskById(taskId).orElse(next);
    }

    public Task create(NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        Instant now = Instant.now();
        Task task = new Task(
                UUID.randomUUID().toString(),
                request.kind(),
                request.provider(),
                /* agentSessionId */ null,
                request.title(),
                TaskStatus.PENDING,
                request.workingDir(),
                request.branchName(),
                request.model(),
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* processPid */ null,
                /* logPath */ null,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                request.metadataJson() == null ? "{}" : request.metadataJson(),
                request.groupId());
        store.saveTask(task);
        // Spin up the session synchronously so the first send() call
        // inside this request can dispatch on it.
        AgentSession session = registry.getOrCreate(task);
        if (request.initialPrompt() != null && !request.initialPrompt().isBlank()) {
            session.send(request.initialPrompt());
        }
        return store.findTaskById(task.id()).orElse(task);
    }

    public Optional<Task> find(String taskId)
    {
        return store.findTaskById(taskId);
    }

    public List<TaskFile> files(String taskId)
    {
        return store.listFiles(taskId);
    }

    public List<TaskMessage> history(String taskId)
    {
        return store.listMessages(taskId);
    }

    /** Send a follow-up turn to an existing task. Re-creates the
     *  in-memory session if it was evicted (e.g. after restart). */
    public void send(String taskId, String input)
    {
        sessionOrThrow(taskId).send(input);
    }

    public void interrupt(String taskId)
    {
        sessionOrThrow(taskId).interrupt();
    }

    public void pause(String taskId)
    {
        sessionOrThrow(taskId).pause();
    }

    public void resume(String taskId)
    {
        sessionOrThrow(taskId).resume();
    }

    public void stop(String taskId)
    {
        sessionOrThrow(taskId).stop();
        registry.evict(taskId);
    }

    /**
     * Permanently removes a task and its conversation / file history.
     * Only terminal tasks ({@code COMPLETED} / {@code ERRORED}) are
     * eligible — live sessions must be {@link #stop stopped} first
     * so we never delete a row that has an in-flight subprocess
     * holding a session id. Idempotent on missing ids: a delete-then-
     * delete from a racing tab just returns silently.
     */
    public void delete(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Optional<Task> existing = store.findTaskById(taskId);
        if (existing.isEmpty()) {
            return;
        }
        TaskStatus status = existing.get().status();
        if (status != TaskStatus.COMPLETED && status != TaskStatus.ERRORED) {
            throw new IllegalStateException(
                    "Task " + taskId + " is " + status + "; only COMPLETED or ERRORED tasks can be deleted");
        }
        // Defensive — if a session got registered post-completion
        // (e.g. resumed and re-terminated), evict it before removing
        // the row. No-op when nothing's cached.
        registry.evict(taskId);
        store.deleteTask(taskId);
    }

    /** Surface a permission prompt in the conversation pane. Called
     *  by the MCP controller when Claude's {@code approval_prompt}
     *  tool fires. */
    public void notifyPermissionRequested(String taskId, String callId, String toolName, String summary)
    {
        sessionOrThrow(taskId).notifyPermissionRequested(callId, toolName, summary);
    }

    public void decide(String taskId, String callId, PermissionDecision decision)
    {
        sessionOrThrow(taskId).decide(callId, decision);
    }

    /** Subscribe to live events. The returned {@link Runnable}
     *  unsubscribes — controllers wire it to the SSE
     *  {@code onCompletion}/{@code onTimeout} callbacks. */
    public Runnable subscribe(String taskId, Consumer<StreamEvent> listener)
    {
        return sessionOrThrow(taskId).subscribeToEvents(listener);
    }

    private AgentSession sessionOrThrow(String taskId)
    {
        Task task = store.findTaskById(taskId)
                .orElseThrow(() -> new NoSuchElementException("no task: " + taskId));
        return registry.getOrCreate(task);
    }

    /** Inputs from the create-task dialog. Kept as a record next to
     *  the service so controllers don't have to define a near-identical
     *  request DTO. */
    public record NewTaskRequest(
            TaskKind kind,
            String provider,
            String model,
            String title,
            String workingDir,
            String branchName,
            String initialPrompt,
            String metadataJson,
            /** Optional — pre-assigns the new task to a group. */
            String groupId) {}

    /** Inputs from the create-group dialog. */
    public record NewGroupRequest(
            String name,
            String glyph,
            String color,
            int sortOrder) {}

    /** Partial-update inputs from the Group settings dialog. {@code null}
     *  or blank fields preserve the current value. */
    public record GroupPatch(
            String name,
            String glyph,
            String color) {}

    /**
     * Partial-update inputs for one task. Fields:
     * <ul>
     *   <li>{@code title} — when non-null and non-blank, replaces the
     *       current title (trimmed); otherwise no change.</li>
     *   <li>{@code group} — present means change the pin to this
     *       value (use {@link GroupRef#clear()} to unpin); absent
     *       (null) means leave the existing groupId alone.</li>
     * </ul>
     * The {@code group} carrier exists because plain {@code null}
     * can't distinguish "don't change" from "unset" — the wrapper
     * surfaces the intent explicitly.
     */
    public record TaskPatch(String title, GroupRef group) {}

    /** Three-state pin update: {@code value() == null} clears the
     *  pin, a non-null value sets it. The carrier is absent (the
     *  patch's {@code group} field is null) when the caller doesn't
     *  want to touch the pin at all. */
    public record GroupRef(String value)
    {
        public static GroupRef of(String groupId) { return new GroupRef(groupId); }
        public static GroupRef clear() { return new GroupRef(null); }
    }
}
