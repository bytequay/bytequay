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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadGroupStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
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
 * subscribes to events — ThreadService offers exactly those verbs.
 */
@Service
public class ThreadService
{
    /** Cap on any single diff payload — 256 KB lets a couple-thousand-
     *  line file through, and stops a misclick on a generated artifact
     *  from blowing up the IPC channel. Matches the spirit of caps used
     *  elsewhere (check-run logs at 200 KB tail). */
    private static final int DIFF_MAX_BYTES = 256 * 1024;
    /** Hard cap on commits returned to the Commits tab. The view is a
     *  fast switcher, not a full git history — 100 is more than enough. */
    private static final int COMMITS_LIMIT = 100;
    /** Cap on threads per group, matching the tile grid in
     *  {@code docs/mockups/design/tasks/thread-group.png} which lays out
     *  1 / 2 / 3 / 4 tiles by count and has no scroll / overflow path. */
    public static final int GROUP_MAX_MEMBERS = 4;
    /** Small history window for scheduler turns. The full conversation
     *  still lives under messages; this is for queue/running state. */
    private static final int TURN_HISTORY_LIMIT = 50;
    /** Recent scheduler-event history for explaining thread turn state. */
    private static final int TURN_EVENT_HISTORY_LIMIT = 200;
    /** Active-turn list cap for thread-list and group-page summaries. */
    private static final int ACTIVE_TURN_LIMIT = 500;

    private final ThreadStore store;
    private final TaskStore taskStore;
    private final ThreadGroupStore groupStore;
    private final ThreadTurnStore turnStore;
    private final ThreadTurnEventStore turnEventStore;
    private final ThreadRegistry registry;
    private final ThreadTurnScheduler scheduler;
    private final WorktreeLeaseService leases;
    private final NotificationService notifications;
    private final GitRunner git;
    private final WorktreeService worktreeService;

    public ThreadService(
            ThreadStore store,
            TaskStore taskStore,
            ThreadGroupStore groupStore,
            ThreadTurnStore turnStore,
            ThreadTurnEventStore turnEventStore,
            ThreadRegistry registry,
            ThreadTurnScheduler scheduler,
            WorktreeLeaseService leases,
            NotificationService notifications,
            GitRunner git,
            WorktreeService worktreeService)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.groupStore = requireNonNull(groupStore, "groupStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.leases = requireNonNull(leases, "leases is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.git = requireNonNull(git, "git is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
    }

    public List<Thread> listByStatus(ThreadStatus status, int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        return store.listTasksByStatus(status, limit);
    }

    public List<Thread> listByGroup(String groupId, int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        List<String> memberIds = groupStore.listMembers(groupId).stream()
                .map(ThreadGroupMembership::threadId)
                .toList();
        if (memberIds.isEmpty()) {
            return List.of();
        }
        List<Thread> rows = store.listTasksByIds(memberIds);
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }

    public List<ThreadGroup> listGroups()
    {
        return groupStore.listGroups();
    }

    /** Full membership snapshot — drives the frontend's thread↔group
     *  index without an N+1 trip per thread. */
    public List<ThreadGroupMembership> listAllMemberships()
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
    public ThreadGroup createGroup(NewGroupRequest request)
    {
        requireNonNull(request, "request is null");
        List<String> initialTaskIds = request.initialTaskIds() == null
                ? List.of()
                : distinctCopy(request.initialTaskIds());
        if (initialTaskIds.isEmpty()) {
            throw new IllegalArgumentException("A group must be created with at least one thread.");
        }
        if (initialTaskIds.size() > GROUP_MAX_MEMBERS) {
            throw new IllegalArgumentException(
                    "A group caps at " + GROUP_MAX_MEMBERS + " threads; got " + initialTaskIds.size() + ".");
        }
        for (String threadId : initialTaskIds) {
            if (store.findThreadById(threadId).isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), "no thread: " + threadId);
            }
        }
        Instant now = Instant.now();
        ThreadGroup group = new ThreadGroup(
                UUID.randomUUID().toString(),
                request.name(),
                request.glyph() == null || request.glyph().isBlank() ? "•" : request.glyph(),
                request.color() == null || request.color().isBlank() ? "slate" : request.color(),
                request.sortOrder(),
                now,
                now);
        groupStore.saveGroup(group);
        for (String threadId : initialTaskIds) {
            groupStore.addMember(threadId, group.id());
        }
        return groupStore.findGroupById(group.id()).orElse(group);
    }

    /**
     * Add one thread to an existing group. Validates both rows exist
     * and that the group has room (cap = {@link #GROUP_MAX_MEMBERS}).
     * Adding an existing member is a no-op (the store dedupes via the
     * composite PK).
     */
    @Transactional
    public void addTaskToGroup(String threadId, String groupId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(groupId, "groupId is null");
        if (groupStore.findGroupById(groupId).isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), "no group: " + groupId);
        }
        if (store.findThreadById(threadId).isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), "no thread: " + threadId);
        }
        long existing = groupStore.countMembers(groupId);
        // Re-adding an existing member shouldn't trip the cap: the
        // store's addMember is idempotent so the count won't change.
        boolean alreadyMember = groupStore.listMembers(groupId).stream()
                .anyMatch(m -> m.threadId().equals(threadId));
        if (!alreadyMember && existing >= GROUP_MAX_MEMBERS) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "Group " + groupId + " is full (" + GROUP_MAX_MEMBERS + " threads); "
                            + "remove one before adding another.");
        }
        groupStore.addMember(threadId, groupId);
    }

    /**
     * Remove one thread from a group. The last member can't be removed
     * — callers must {@link #deleteGroup} the group instead. Removing
     * a thread that isn't a member is a no-op.
     */
    @Transactional
    public void removeTaskFromGroup(String threadId, String groupId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(groupId, "groupId is null");
        if (groupStore.findGroupById(groupId).isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), "no group: " + groupId);
        }
        boolean isMember = groupStore.listMembers(groupId).stream()
                .anyMatch(m -> m.threadId().equals(threadId));
        if (!isMember) {
            return;
        }
        if (groupStore.countMembers(groupId) <= 1) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "Group " + groupId + " has only one thread left; "
                            + "delete the group instead of emptying it.");
        }
        groupStore.removeMember(threadId, groupId);
    }

    /** Partial update — only non-null fields on {@code patch} change.
     *  Mirrors the Group settings panel: edit any of name/glyph/color
     *  independently. */
    public ThreadGroup updateGroup(String groupId, GroupPatch patch)
    {
        requireNonNull(groupId, "groupId is null");
        requireNonNull(patch, "patch is null");
        ThreadGroup current = groupStore.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "no group: " + groupId));
        ThreadGroup next = new ThreadGroup(
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

    /** Delete a group. The {@code thread_group_members} cascade in the
     *  schema (V59) drops the membership rows; the threads themselves
     *  survive — they simply leave the group. */
    public void deleteGroup(String groupId)
    {
        groupStore.deleteGroup(groupId);
    }

    /**
     * Partial update for one thread. {@code title} is the only editable
     * field; pass a non-null non-blank string to rename, or omit /
     * pass null to leave the title alone. Group membership lives in
     * its own endpoints ({@link #addTaskToGroup} /
     * {@link #removeTaskFromGroup}) since one thread can belong to
     * several groups.
     */
    public Thread patchTask(String threadId, TaskPatch patch)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(patch, "patch is null");
        Thread current = store.findThreadById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "no thread: " + threadId));

        String nextTitle = current.title();
        if (patch.title() != null && !patch.title().isBlank()) {
            nextTitle = patch.title().trim();
        }

        Thread next = new Thread(
                current.id(), current.kind(), current.provider(), current.agentSessionId(),
                nextTitle, current.status(), current.workingDir(), current.branchName(),
                current.model(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.processPid(), current.logPath(),
                current.createdAt(), Instant.now(),
                current.endedAt(), current.errorMessage(), current.metadataJson(),
                current.taskType(), current.linkedPrNumber(), current.linkedIssueNumber(),
                current.worktreePath(), current.localBranch(),
                current.flow());
        store.saveThread(next);
        return store.findThreadById(threadId).orElse(next);
    }

    /**
     * Create + start a thread. Optionally pin it into one or more
     * existing groups via {@link NewTaskRequest#initialGroupIds}; each
     * referenced group must exist and have room (the
     * {@link #GROUP_MAX_MEMBERS} cap). The persistence of the thread
     * and its memberships happens in one transaction so a half-pinned
     * thread can't survive a mid-flight failure.
     */
    @Transactional
    public Thread create(NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        List<String> initialGroupIds = request.initialGroupIds() == null
                ? List.of()
                : distinctCopy(request.initialGroupIds());
        for (String groupId : initialGroupIds) {
            if (groupStore.findGroupById(groupId).isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), "no group: " + groupId);
            }
            if (groupStore.countMembers(groupId) >= GROUP_MAX_MEMBERS) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "Group " + groupId + " is full (" + GROUP_MAX_MEMBERS + " threads); "
                                + "remove one before adding another.");
            }
        }
        Instant now = Instant.now();
        String taskType = request.taskType() == null || request.taskType().isBlank()
                ? "DEVELOP"
                : request.taskType().trim();
        String threadId = UUID.randomUUID().toString();
        // The new model names the worktree directory after the task id
        // (one worktree per task), so we generate the first task's id
        // up-front and reuse it as the on-disk name.
        String firstTaskId = UUID.randomUUID().toString();
        // Best-effort worktree creation. Failures fall through to a
        // null handle so the agent runs in the main checkout — keeps
        // threads against non-git working dirs or read-only repos
        // working without a special-case.
        Optional<WorktreeService.WorktreeHandle> handle =
                request.workingDir() == null || request.workingDir().isBlank()
                        ? Optional.empty()
                        : worktreeService.create(
                                Path.of(request.workingDir()), firstTaskId, request.title());
        Thread thread = new Thread(
                threadId,
                request.kind(),
                request.provider(),
                /* agentSessionId */ null,
                request.title(),
                ThreadStatus.PENDING,
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
                request.linkedIssueNumber(),
                handle.map(h -> h.worktreePath().toString()).orElse(null),
                handle.map(WorktreeService.WorktreeHandle::branchName).orElse(null),
                request.flow() == null ? ThreadFlow.BUILD : request.flow());
        store.saveThread(thread);
        // Materialise the first task row with a chosen id so the on-disk
        // worktree dir name (which used firstTaskId) matches it. The
        // saveThread call above's transparent task-create branch would
        // have done this with an auto-generated id; we override that by
        // creating the row explicitly here.
        Task existing = taskStore.findActiveTaskForThread(threadId).orElse(null);
        if (existing != null && !existing.id().equals(firstTaskId)) {
            Task aligned = new Task(
                    firstTaskId, threadId, existing.seq(), existing.status(),
                    existing.branchName(), existing.worktreePath(), existing.baseBranch(),
                    existing.workingDir(), existing.processPid(), existing.logPath(),
                    existing.prNumber(), existing.prState(), existing.ciState(),
                    existing.taskType(), existing.linkedPrNumber(), existing.linkedIssueNumber(),
                    existing.costUsdMilli(), existing.tokensIn(), existing.tokensOut(),
                    existing.firstMsgSeq(), existing.lastMsgSeq(),
                    existing.createdAt(), existing.endedAt(), existing.errorMessage());
            taskStore.deleteTask(existing.id());
            taskStore.saveTask(aligned);
        }
        else if (existing == null && handle.isPresent()) {
            // saveThread didn't auto-create (no execution state for the
            // store to spot) but we have a worktree — record it anyway.
            taskStore.saveTask(new Task(
                    firstTaskId, threadId, 1L, TaskStatus.PENDING,
                    handle.get().branchName(),
                    handle.get().worktreePath().toString(),
                    "main",
                    request.workingDir(),
                    /* processPid */ null, /* logPath */ null,
                    null, null, null,
                    taskType, request.linkedPrNumber(), request.linkedIssueNumber(),
                    0L, 0L, 0L,
                    null, null,
                    now, null, null));
        }
        for (String groupId : initialGroupIds) {
            groupStore.addMember(thread.id(), groupId);
        }
        if (request.initialPrompt() != null && !request.initialPrompt().isBlank()) {
            scheduler.enqueueTurn(thread, request.initialPrompt());
        }
        return store.findThreadById(thread.id()).orElse(thread);
    }

    public Optional<Thread> find(String threadId)
    {
        return store.findThreadById(threadId);
    }

    public List<ThreadFile> files(String threadId)
    {
        return store.listFiles(threadId);
    }

    public List<ThreadMessage> history(String threadId)
    {
        return store.listMessages(threadId);
    }

    /** Recent scheduler turns for one thread, newest first. */
    public List<ThreadTurn> turns(String threadId)
    {
        return turnStore.listTurnsByTaskId(requireTask(threadId).id(), TURN_HISTORY_LIMIT);
    }

    /** Recent scheduler events for one thread, newest first. */
    public List<ThreadTurnEvent> turnEvents(String threadId)
    {
        return turnEventStore.listEventsByTaskId(requireTask(threadId).id(), TURN_EVENT_HISTORY_LIMIT);
    }

    /** Queued/running turns across all threads, oldest first. */
    public List<ThreadTurn> activeTurns(int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        return turnStore.listTurnsByStatuses(
                List.of(ThreadTurnStatus.RUNNING, ThreadTurnStatus.QUEUED),
                Math.min(limit, ACTIVE_TURN_LIMIT));
    }

    /** Send a follow-up turn to an existing thread. Re-creates the
     *  in-memory session if it was evicted (e.g. after restart). */
    public String send(String threadId, String input)
    {
        return scheduler.enqueueTurn(requireTask(threadId), input);
    }

    public void interrupt(String threadId)
    {
        sessionOrThrow(threadId).interrupt();
    }

    /**
     * Take control of a thread away from any in-flight headless run.
     * Drives the "Jump in" button on parked notifications, per the
     * model doc's jump-in flow:
     *
     *   * interrupt the live session (if any) so a mid-flight CLI
     *     subprocess exits at the next tool boundary and releases its
     *     worktree lease on its own shutdown path,
     *   * defensively release the lease too in case the holder is
     *     already gone but the row wasn't cleaned up (the reaper does
     *     the same thing every minute, but the user clicking Jump in
     *     shouldn't have to wait for it),
     *   * mark any UNREAD NEEDS_ATTENTION / AWAITING_REVIEW
     *     notifications for this thread as read so the bell quiets
     *     down — the user is *here* now.
     *
     * <p>Returns the thread snapshot the controller hands back, so the
     * caller sees status / cost / token counts immediately after the
     * transfer.
     *
     * <p>Unlike {@link #interrupt}, this method is forgiving when no
     * live session exists — the thread might have been evicted from
     * the registry after the headless turn already finished and the
     * user is jumping in to a quiescent thread. Cleaning up the lease
     * + notifications is still useful in that case.
     */
    public Thread jumpIn(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Thread thread = requireTask(threadId);
        registry.find(threadId).ifPresent(session -> {
            if (thread.status() == ThreadStatus.RUNNING
                    || thread.status() == ThreadStatus.AWAITING) {
                session.interrupt();
            }
        });
        // Release the lease on the active task's worktree. The CLI
        // agent's shutdown path also releases on exit; doing it here
        // makes the transfer atomic from the user's perspective.
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        active.map(Task::worktreePath)
                .filter(p -> p != null && !p.isBlank())
                .ifPresent(leases::release);
        // Quiet the bell for this thread. Parked notifications are
        // the trigger for jump-in, so leaving them UNREAD after the
        // user has actively transferred would mis-report attention.
        for (Notification n : notifications.listForThread(threadId)) {
            if (n.status() == NotificationStatus.UNREAD
                    && (n.kind() == NotificationKind.NEEDS_ATTENTION
                            || n.kind() == NotificationKind.AWAITING_REVIEW)) {
                notifications.markRead(n.id());
            }
        }
        return store.findThreadById(threadId).orElse(thread);
    }

    public void pause(String threadId)
    {
        sessionOrThrow(threadId).pause();
    }

    public void resume(String threadId)
    {
        sessionOrThrow(threadId).resume();
    }

    public void stop(String threadId)
    {
        requireTask(threadId);
        scheduler.cancelQueuedTurns(threadId);
        registry.find(threadId).ifPresent(session -> {
            session.stop();
            registry.evict(threadId);
        });
    }

    /**
     * Permanently removes a thread and its conversation / file history.
     * Only terminal threads ({@code COMPLETED} / {@code ERRORED}) are
     * eligible — live sessions must be {@link #stop stopped} first
     * so we never delete a row that has an in-flight subprocess
     * holding a session id. Idempotent on missing ids: a delete-then-
     * delete from a racing tab just returns silently.
     */
    public void delete(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Optional<Thread> existing = store.findThreadById(threadId);
        if (existing.isEmpty()) {
            return;
        }
        ThreadStatus status = existing.get().status();
        if (status != ThreadStatus.COMPLETED && status != ThreadStatus.ERRORED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "Thread " + threadId + " is " + status + "; only COMPLETED or ERRORED threads can be deleted");
        }
        // Defensive — if a session got registered post-completion
        // (e.g. resumed and re-terminated), evict it before removing
        // the row. No-op when nothing's cached.
        scheduler.cancelQueuedTurns(threadId);
        registry.evict(threadId);
        // Best-effort worktree cleanup. Errors are logged inside the
        // service; we don't fail the delete if the worktree is already
        // gone or git can't remove it cleanly — the thread row going
        // away is the authoritative signal.
        Thread thread = existing.get();
        if (thread.worktreePath() != null && !thread.worktreePath().isBlank()
                && thread.workingDir() != null && !thread.workingDir().isBlank()) {
            worktreeService.remove(Path.of(thread.workingDir()),
                    thread.worktreePath(), thread.localBranch());
        }
        store.deleteThread(threadId);
    }

    // ── Working-tree + commit views for the Tasks UI tabs ────────────
    // Each call resolves the thread to its agent cwd, then delegates
    // to GitRunner. We don't cache — these views are opened on demand
    // and the underlying tree mutates as the AI session writes files.
    // Wrap the checked IO/Interrupted exceptions so callers stay clean.

    /** Files the AI session has modified but not yet committed —
     *  feeds the "Files" tab. Mirrors {@code git status --porcelain}. */
    public List<GitRunner.WorkingTreeFile> listWorkingChanges(String threadId)
    {
        Thread thread = requireTask(threadId);
        try {
            return git.workingTreeFiles(agentCwd(thread));
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list working-tree changes for " + threadId, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing working-tree changes for " + threadId, e);
        }
    }

    /** Unified diff for one uncommitted file. */
    public String getWorkingDiff(String threadId, String path)
    {
        requireNonNull(path, "path is null");
        Thread thread = requireTask(threadId);
        try {
            return git.workingTreeFileDiff(agentCwd(thread), path, DIFF_MAX_BYTES);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to diff " + path + " for " + threadId, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted diffing " + path + " for " + threadId, e);
        }
    }

    /** Commits authored during the thread's lifetime. Time-based filter
     *  on the thread's {@code createdAt} — works for single-user repos
     *  where anything new since the AI session started is the AI's. */
    public List<GitRunner.CommitEntry> listTaskCommits(String threadId)
    {
        Thread thread = requireTask(threadId);
        try {
            return git.listCommitsSince(agentCwd(thread), thread.createdAt(), COMMITS_LIMIT);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list commits for " + threadId, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing commits for " + threadId, e);
        }
    }

    /** Per-file rollup for one commit (path + status + +/-) so the
     *  Commits tab can render a sub-list inside an expanded commit. */
    public List<GitRunner.CommitFileChange> listCommitFiles(String threadId, String sha)
    {
        requireNonNull(sha, "sha is null");
        Thread thread = requireTask(threadId);
        try {
            return git.commitFiles(agentCwd(thread), sha);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to list files for commit " + sha + " (thread " + threadId + ")", e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted listing files for commit " + sha, e);
        }
    }

    /** Unified diff for one file at one commit. */
    public String getCommitDiff(String threadId, String sha, String path)
    {
        requireNonNull(sha, "sha is null");
        requireNonNull(path, "path is null");
        Thread thread = requireTask(threadId);
        try {
            return git.commitFileDiff(agentCwd(thread), sha, path, DIFF_MAX_BYTES);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to diff " + path + " at " + sha + " (thread " + threadId + ")", e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted diffing " + path + " at " + sha, e);
        }
    }

    private Thread requireTask(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        return store.findThreadById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "no thread: " + threadId));
    }

    private static Path agentCwd(Thread thread)
    {
        return Path.of(thread.agentCwd());
    }

    private static List<String> distinctCopy(List<String> values)
    {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /** Surface a permission prompt in the conversation pane. Called
     *  by the MCP controller when Claude's {@code approval_prompt}
     *  tool fires. */
    public void notifyPermissionRequested(String threadId, String callId, String toolName, String summary)
    {
        sessionOrThrow(threadId).notifyPermissionRequested(callId, toolName, summary);
    }

    public void decide(String threadId, String callId, PermissionDecision decision)
    {
        sessionOrThrow(threadId).decide(callId, decision);
    }

    /** Pre-authorise the next {@code count} invocations of {@code toolName}
     *  for the given thread. {@code count == -1} means "always". The MCP
     *  controller calls {@link #tryConsumeToolBudget} to drain this budget
     *  before surfacing a prompt to the user. */
    public void grantToolBudget(String threadId, String toolName, int count)
    {
        sessionOrThrow(threadId).grantToolBudget(toolName, count);
    }

    /** Called from the MCP hot path — quiet (returns
     *  {@link OptionalInt#empty()}) if the session is gone instead of
     *  throwing, since a stale prompt shouldn't take down the
     *  controller. The returned int is the remaining budget after the
     *  consumption ({@code -1} for an ALWAYS grant); empty means the
     *  call should fall through to the normal user prompt. */
    public OptionalInt tryConsumeToolBudget(String threadId, String toolName)
    {
        try {
            return sessionOrThrow(threadId).tryConsumeToolBudget(toolName);
        }
        catch (NoSuchElementException ignored) {
            return OptionalInt.empty();
        }
    }

    /** Persist + publish a {@code permission_auto_allowed} notice so
     *  the conversation pane can show the user which tool was auto-
     *  approved by their pre-approval budget, and how many slots are
     *  left. */
    public void notifyPermissionAutoAllowed(String threadId, String callId, String toolName, int remaining)
    {
        try {
            sessionOrThrow(threadId).notifyPermissionAutoAllowed(callId, toolName, remaining);
        }
        catch (NoSuchElementException ignored) {
            // session vanished — nothing to notify
        }
    }

    /** Subscribe to live events. The returned {@link Runnable}
     *  unsubscribes — controllers wire it to the SSE
     *  {@code onCompletion}/{@code onTimeout} callbacks. */
    public Runnable subscribe(String threadId, Consumer<StreamEvent> listener)
    {
        return sessionOrThrow(threadId).subscribeToEvents(listener);
    }

    private ThreadAgent sessionOrThrow(String threadId)
    {
        Thread thread = store.findThreadById(threadId)
                .orElseThrow(() -> new NoSuchElementException("no thread: " + threadId));
        return registry.getOrCreate(thread);
    }

    /**
     * Inputs from the create-thread dialog. Kept as a record next to the service
     * so controllers don't have to define a near-identical request DTO.
     *
     * @param initialGroupIds optional group ids to pre-assign the new thread to.
     * @param taskType free-form thread type.
     * @param linkedPrNumber optional GitHub PR number to link the thread to.
     * @param linkedIssueNumber optional GitHub issue number to link the thread to.
     * @param flow build vs review discriminator. Null defaults to
     * {@link ThreadFlow#BUILD}; once set on a thread it can't be
     * silently flipped (see SqliteThreadStore.saveThread).
     */
    public record NewTaskRequest(
            ThreadKind kind,
            String provider,
            String model,
            String title,
            String workingDir,
            String branchName,
            String initialPrompt,
            String metadataJson,
            List<String> initialGroupIds,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            ThreadFlow flow) {}

    /** Inputs from the create-group dialog. The redesign requires
     *  a non-empty group, so {@code initialTaskIds} is required (≥1
     *  thread) and bounded by {@link #GROUP_MAX_MEMBERS}. */
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
     * Partial-update inputs for one thread. Only {@code title} is
     * editable; pass a non-null, non-blank string to rename. Group
     * membership lives in {@link ThreadService#addTaskToGroup} /
     * {@link ThreadService#removeTaskFromGroup} since one thread can
     * belong to several groups.
     */
    public record TaskPatch(String title) {}
}
