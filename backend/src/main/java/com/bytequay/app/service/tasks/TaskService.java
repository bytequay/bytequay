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
import com.bytequay.app.domain.TaskGroupMembership;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskGroupStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
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
    /** Cap on any single diff payload — 256 KB lets a couple-thousand-
     *  line file through, and stops a misclick on a generated artifact
     *  from blowing up the IPC channel. Matches the spirit of caps used
     *  elsewhere (check-run logs at 200 KB tail). */
    private static final int DIFF_MAX_BYTES = 256 * 1024;
    /** Hard cap on commits returned to the Commits tab. The view is a
     *  fast switcher, not a full git history — 100 is more than enough. */
    private static final int COMMITS_LIMIT = 100;
    /** Cap on tasks per group, matching the tile grid in
     *  {@code docs/mockups/design/tasks/tasks-group.png} which lays out
     *  1 / 2 / 3 / 4 tiles by count and has no scroll / overflow path. */
    public static final int GROUP_MAX_MEMBERS = 4;

    private final TaskStore store;
    private final TaskGroupStore groupStore;
    private final TaskSessionRegistry registry;
    private final GitRunner git;

    public TaskService(
            TaskStore store,
            TaskGroupStore groupStore,
            TaskSessionRegistry registry,
            GitRunner git)
    {
        this.store = requireNonNull(store, "store is null");
        this.groupStore = requireNonNull(groupStore, "groupStore is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.git = requireNonNull(git, "git is null");
    }

    public List<Task> listByStatus(TaskStatus status, int limit)
    {
        return store.listTasksByStatus(status, limit);
    }

    public List<Task> listByGroup(String groupId, int limit)
    {
        List<String> memberIds = groupStore.listMembers(groupId).stream()
                .map(TaskGroupMembership::taskId)
                .toList();
        if (memberIds.isEmpty()) {
            return List.of();
        }
        List<Task> rows = store.listTasksByIds(memberIds);
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }

    public List<TaskGroup> listGroups()
    {
        return groupStore.listGroups();
    }

    /** Full membership snapshot — drives the frontend's task↔group
     *  index without an N+1 trip per task. */
    public List<TaskGroupMembership> listAllMemberships()
    {
        return groupStore.listAllMemberships();
    }

    /**
     * Create a group with its initial membership in one transaction.
     *
     * <p>Enforces both invariants from the redesign:
     * <ul>
     *   <li>At least one initial member ({@code initialTaskIds} must
     *       not be empty) — there is no path to an empty group.</li>
     *   <li>At most {@link #GROUP_MAX_MEMBERS} initial members so the
     *       new group fits the tile grid without overflow.</li>
     * </ul>
     */
    @Transactional
    public TaskGroup createGroup(NewGroupRequest request)
    {
        requireNonNull(request, "request is null");
        List<String> initialTaskIds = request.initialTaskIds() == null
                ? List.of()
                : List.copyOf(request.initialTaskIds());
        if (initialTaskIds.isEmpty()) {
            throw new IllegalArgumentException("A group must be created with at least one task.");
        }
        if (initialTaskIds.size() > GROUP_MAX_MEMBERS) {
            throw new IllegalArgumentException(
                    "A group caps at " + GROUP_MAX_MEMBERS + " tasks; got " + initialTaskIds.size() + ".");
        }
        for (String taskId : initialTaskIds) {
            if (store.findTaskById(taskId).isEmpty()) {
                throw new NoSuchElementException("no task: " + taskId);
            }
        }
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
        for (String taskId : initialTaskIds) {
            groupStore.addMember(taskId, group.id());
        }
        return groupStore.findGroupById(group.id()).orElse(group);
    }

    /**
     * Add one task to an existing group. Validates both rows exist
     * and that the group has room (cap = {@link #GROUP_MAX_MEMBERS}).
     * Adding an existing member is a no-op (the store dedupes via the
     * composite PK).
     */
    @Transactional
    public void addTaskToGroup(String taskId, String groupId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(groupId, "groupId is null");
        if (groupStore.findGroupById(groupId).isEmpty()) {
            throw new NoSuchElementException("no group: " + groupId);
        }
        if (store.findTaskById(taskId).isEmpty()) {
            throw new NoSuchElementException("no task: " + taskId);
        }
        long existing = groupStore.countMembers(groupId);
        // Re-adding an existing member shouldn't trip the cap: the
        // store's addMember is idempotent so the count won't change.
        boolean alreadyMember = groupStore.listMembers(groupId).stream()
                .anyMatch(m -> m.taskId().equals(taskId));
        if (!alreadyMember && existing >= GROUP_MAX_MEMBERS) {
            throw new IllegalStateException(
                    "Group " + groupId + " is full (" + GROUP_MAX_MEMBERS + " tasks); "
                            + "remove one before adding another.");
        }
        groupStore.addMember(taskId, groupId);
    }

    /**
     * Remove one task from a group. The last member can't be removed
     * — callers must {@link #deleteGroup} the group instead. Removing
     * a task that isn't a member is a no-op.
     */
    @Transactional
    public void removeTaskFromGroup(String taskId, String groupId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(groupId, "groupId is null");
        if (groupStore.findGroupById(groupId).isEmpty()) {
            throw new NoSuchElementException("no group: " + groupId);
        }
        boolean isMember = groupStore.listMembers(groupId).stream()
                .anyMatch(m -> m.taskId().equals(taskId));
        if (!isMember) {
            return;
        }
        if (groupStore.countMembers(groupId) <= 1) {
            throw new IllegalStateException(
                    "Group " + groupId + " has only one task left; "
                            + "delete the group instead of emptying it.");
        }
        groupStore.removeMember(taskId, groupId);
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

    /** Delete a group. The {@code task_group_members} cascade in the
     *  schema (V59) drops the membership rows; the tasks themselves
     *  survive — they simply leave the group. */
    public void deleteGroup(String groupId)
    {
        groupStore.deleteGroup(groupId);
    }

    /**
     * Partial update for one task. {@code title} is the only editable
     * field; pass a non-null non-blank string to rename, or omit /
     * pass null to leave the title alone. Group membership lives in
     * its own endpoints ({@link #addTaskToGroup} /
     * {@link #removeTaskFromGroup}) since one task can belong to
     * several groups.
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

        Task next = new Task(
                current.id(), current.kind(), current.provider(), current.agentSessionId(),
                nextTitle, current.status(), current.workingDir(), current.branchName(),
                current.model(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.processPid(), current.logPath(),
                current.createdAt(), Instant.now(),
                current.endedAt(), current.errorMessage(), current.metadataJson(),
                current.taskType(), current.linkedPrNumber(), current.linkedIssueNumber());
        store.saveTask(next);
        return store.findTaskById(taskId).orElse(next);
    }

    /**
     * Create + start a task. Optionally pin it into one or more
     * existing groups via {@link NewTaskRequest#initialGroupIds}; each
     * referenced group must exist and have room (the
     * {@link #GROUP_MAX_MEMBERS} cap). The persistence of the task
     * and its memberships happens in one transaction so a half-pinned
     * task can't survive a mid-flight failure.
     */
    @Transactional
    public Task create(NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        List<String> initialGroupIds = request.initialGroupIds() == null
                ? List.of()
                : List.copyOf(request.initialGroupIds());
        for (String groupId : initialGroupIds) {
            if (groupStore.findGroupById(groupId).isEmpty()) {
                throw new NoSuchElementException("no group: " + groupId);
            }
            if (groupStore.countMembers(groupId) >= GROUP_MAX_MEMBERS) {
                throw new IllegalStateException(
                        "Group " + groupId + " is full (" + GROUP_MAX_MEMBERS + " tasks); "
                                + "remove one before adding another.");
            }
        }
        Instant now = Instant.now();
        String taskType = request.taskType() == null || request.taskType().isBlank()
                ? "DEVELOP"
                : request.taskType().trim();
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
                taskType,
                request.linkedPrNumber(),
                request.linkedIssueNumber());
        store.saveTask(task);
        for (String groupId : initialGroupIds) {
            groupStore.addMember(task.id(), groupId);
        }
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

    // ── Working-tree + commit views for the Tasks UI tabs ────────────
    // Each call resolves the task to its workingDir, then delegates
    // to GitRunner. We don't cache — these views are opened on demand
    // and the underlying tree mutates as the AI session writes files.
    // Wrap the checked IO/Interrupted exceptions so callers stay clean.

    /** Files the AI session has modified but not yet committed —
     *  feeds the "Files" tab. Mirrors {@code git status --porcelain}. */
    public List<GitRunner.WorkingTreeFile> listWorkingChanges(String taskId)
    {
        Task task = requireTask(taskId);
        try {
            return git.workingTreeFiles(Path.of(task.workingDir()));
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list working-tree changes for " + taskId, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing working-tree changes for " + taskId, e);
        }
    }

    /** Unified diff for one uncommitted file. */
    public String getWorkingDiff(String taskId, String path)
    {
        requireNonNull(path, "path is null");
        Task task = requireTask(taskId);
        try {
            return git.workingTreeFileDiff(Path.of(task.workingDir()), path, DIFF_MAX_BYTES);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to diff " + path + " for " + taskId, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted diffing " + path + " for " + taskId, e);
        }
    }

    /** Commits authored during the task's lifetime. Time-based filter
     *  on the task's {@code createdAt} — works for single-user repos
     *  where anything new since the AI session started is the AI's. */
    public List<GitRunner.CommitEntry> listTaskCommits(String taskId)
    {
        Task task = requireTask(taskId);
        try {
            return git.listCommitsSince(Path.of(task.workingDir()), task.createdAt(), COMMITS_LIMIT);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list commits for " + taskId, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing commits for " + taskId, e);
        }
    }

    /** Per-file rollup for one commit (path + status + +/-) so the
     *  Commits tab can render a sub-list inside an expanded commit. */
    public List<GitRunner.CommitFileChange> listCommitFiles(String taskId, String sha)
    {
        requireNonNull(sha, "sha is null");
        Task task = requireTask(taskId);
        try {
            return git.commitFiles(Path.of(task.workingDir()), sha);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list files for commit " + sha + " (task " + taskId + ")", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing files for commit " + sha, e);
        }
    }

    /** Unified diff for one file at one commit. */
    public String getCommitDiff(String taskId, String sha, String path)
    {
        requireNonNull(sha, "sha is null");
        requireNonNull(path, "path is null");
        Task task = requireTask(taskId);
        try {
            return git.commitFileDiff(Path.of(task.workingDir()), sha, path, DIFF_MAX_BYTES);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to diff " + path + " at " + sha + " (task " + taskId + ")", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted diffing " + path + " at " + sha, e);
        }
    }

    private Task requireTask(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return store.findTaskById(taskId)
                .orElseThrow(() -> new NoSuchElementException("no task: " + taskId));
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

    /** Pre-authorise the next {@code count} invocations of {@code toolName}
     *  for the given task. {@code count == -1} means "always". The MCP
     *  controller calls {@link #tryConsumeToolBudget} to drain this budget
     *  before surfacing a prompt to the user. */
    public void grantToolBudget(String taskId, String toolName, int count)
    {
        sessionOrThrow(taskId).grantToolBudget(toolName, count);
    }

    /** Called from the MCP hot path — quiet (returns
     *  {@link OptionalInt#empty()}) if the session is gone instead of
     *  throwing, since a stale prompt shouldn't take down the
     *  controller. The returned int is the remaining budget after the
     *  consumption ({@code -1} for an ALWAYS grant); empty means the
     *  call should fall through to the normal user prompt. */
    public OptionalInt tryConsumeToolBudget(String taskId, String toolName)
    {
        try {
            return sessionOrThrow(taskId).tryConsumeToolBudget(toolName);
        }
        catch (NoSuchElementException ignored) {
            return OptionalInt.empty();
        }
    }

    /** Persist + publish a {@code permission_auto_allowed} notice so
     *  the conversation pane can show the user which tool was auto-
     *  approved by their pre-approval budget, and how many slots are
     *  left. */
    public void notifyPermissionAutoAllowed(String taskId, String callId, String toolName, int remaining)
    {
        try {
            sessionOrThrow(taskId).notifyPermissionAutoAllowed(callId, toolName, remaining);
        }
        catch (NoSuchElementException ignored) {
            // session vanished — nothing to notify
        }
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
            /** Optional — pre-assigns the new task to one or more
             *  existing groups. Each group must have room (cap =
             *  {@link #GROUP_MAX_MEMBERS}); the whole create is
             *  transactional so the task and its memberships either
             *  all land or none do. */
            List<String> initialGroupIds,
            /** Free-form task type — {@code "DEVELOP"} or
             *  {@code "FIX"} today, more values likely later. Defaults
             *  to {@code "DEVELOP"} when null/blank. */
            String taskType,
            /** Optional GitHub PR number to link the task to. Scoped
             *  to the task's repo, so callers only pass the number. */
            Integer linkedPrNumber,
            /** Optional GitHub issue number, same scoping as the PR. */
            Integer linkedIssueNumber) {}

    /** Inputs from the create-group dialog. The redesign requires
     *  a non-empty group, so {@code initialTaskIds} is required (≥1
     *  task) and bounded by {@link #GROUP_MAX_MEMBERS}. */
    public record NewGroupRequest(
            String name,
            String glyph,
            String color,
            int sortOrder,
            List<String> initialTaskIds) {}

    /** Partial-update inputs from the Group settings dialog. {@code null}
     *  or blank fields preserve the current value. */
    public record GroupPatch(
            String name,
            String glyph,
            String color) {}

    /**
     * Partial-update inputs for one task. Only {@code title} is
     * editable; pass a non-null, non-blank string to rename. Group
     * membership lives in {@link TaskService#addTaskToGroup} /
     * {@link TaskService#removeTaskFromGroup} since one task can
     * belong to several groups.
     */
    public record TaskPatch(String title) {}
}
