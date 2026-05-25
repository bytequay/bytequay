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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ThreadService.class);

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

    /** Workspace-scoped variant of {@link #listByStatus}. The thread
     *  list endpoint routes here whenever the caller passes a
     *  {@code workspaceId} so a newly-created workspace doesn't
     *  render the default workspace's threads. */
    public List<Thread> listByWorkspaceAndStatus(String workspaceId, ThreadStatus status, int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        return store.listTasksByWorkspaceAndStatus(workspaceId, status, limit);
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
                nextTitle, current.status(),
                current.model(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.createdAt(), Instant.now(),
                current.endedAt(), current.errorMessage(),
                current.flow(),
                current.activeTask());
        store.saveThread(next);
        return store.findThreadById(threadId).orElse(next);
    }

    /**
     * Create a 0-Task thread. The thread lands on the trunk
     * (planning) — no branch, no worktree, no Task row. If the caller
     * supplied a non-blank {@code initialPrompt} it is routed as a
     * trunk turn so the planning agent answers without a worktree
     * lease; the title is derived from that prompt when the caller
     * left it null/blank. A Task only materialises later, when work
     * turns branch-worthy — see {@link #materialiseTask}.
     *
     * <p>Optionally pin the new thread into one or more existing
     * groups via {@link NewTaskRequest#initialGroupIds}; each
     * referenced group must exist and have room (the
     * {@link #GROUP_MAX_MEMBERS} cap). Group memberships persist in
     * the same transaction as the thread so a half-pinned thread
     * can't survive a mid-flight failure.
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
        String threadId = UUID.randomUUID().toString();
        String title = deriveTitle(request.title(), request.initialPrompt());
        Thread thread = new Thread(
                threadId,
                request.kind(),
                request.provider(),
                /* agentSessionId */ null,
                title,
                ThreadStatus.PENDING,
                request.model(),
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                request.flow() == null ? ThreadFlow.BUILD : request.flow(),
                /* activeTask */ null);
        store.saveThread(thread);
        for (String groupId : initialGroupIds) {
            groupStore.addMember(thread.id(), groupId);
        }
        // initialPrompt — if present — feeds the title derivation
        // above but is NOT enqueued as a trunk turn. Treat it as
        // setup context the user prepared in the create dialog; the
        // trunk page stages it in the composer so the user reviews
        // before pressing Send. Nothing reaches the planning agent
        // until they do.
        return store.findThreadById(thread.id()).orElse(thread);
    }

    /**
     * Materialise a Task under an existing thread — cuts a dev branch
     * + worktree and (if {@code request.initialPrompt} is non-blank)
     * enqueues a task-scope turn against it. Use this when work turns
     * branch-worthy from the trunk's planning conversation, or when an
     * assign-dev-task action attaches a build Task to a thread.
     *
     * <p>decision pending: today this is an explicit caller-driven
     * path. The trunk's agent-proposed "looks like it'll touch code,
     * start a task?" prompt should call into this method once that
     * proposal UI lands.
     */
    @Transactional
    public Task materialiseTask(String threadId, NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        Thread thread = requireTask(threadId);
        if (request.workingDir() == null || request.workingDir().isBlank()) {
            throw new IllegalArgumentException("workingDir is required to materialise a task");
        }
        Instant now = Instant.now();
        String taskType = request.taskType() == null || request.taskType().isBlank()
                ? "DEVELOP"
                : request.taskType().trim();
        String taskId = UUID.randomUUID().toString();
        // The new model names the worktree directory after the task id
        // (one worktree per task), so the on-disk dir matches the row.
        Optional<WorktreeService.WorktreeHandle> handle = worktreeService.create(
                Path.of(request.workingDir()), taskId, thread.title());
        String branchName = handle
                .map(WorktreeService.WorktreeHandle::branchName)
                .orElse(request.branchName());
        long seq = taskStore.maxSeqForThread(threadId).orElse(0L) + 1L;
        Task task = new Task(
                taskId, threadId, seq, TaskStatus.PENDING,
                branchName,
                handle.map(h -> h.worktreePath().toString()).orElse(null),
                "main",
                request.workingDir(),
                /* processPid */ null, /* logPath */ null,
                null, null, null,
                taskType, request.linkedPrNumber(), request.linkedIssueNumber(),
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, null, null,
                /* name */ null);
        taskStore.saveTask(task);
        Thread refreshed = store.findThreadById(threadId).orElse(thread);
        if (request.initialPrompt() != null && !request.initialPrompt().isBlank()) {
            scheduler.enqueueTurn(refreshed, request.initialPrompt());
        }
        return task;
    }

    private static String deriveTitle(String supplied, String firstMessage)
    {
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }
        if (firstMessage == null || firstMessage.isBlank()) {
            return "New thread";
        }
        // Pick the first non-empty line, take up to ~6 words, cap at
        // 60 chars. Cheap auto-title until the agent rewrites it.
        String firstLine = firstMessage.strip().lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return "New thread";
        }
        String[] words = firstLine.split("\\s+");
        StringBuilder out = new StringBuilder();
        int limit = Math.min(6, words.length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(words[i]);
        }
        String summary = out.toString();
        if (summary.length() > 60) {
            summary = summary.substring(0, 57) + "…";
        }
        return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
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

    /** Trunk-scope counterpart of {@link #send} — drives the trunk
     *  planning agent for cross-task talk. The row lands with
     *  {@code task_id = null} so it filters into the trunk slice
     *  rather than any Task's segment. */
    public String sendTrunk(String threadId, String input)
    {
        return scheduler.enqueueTrunkTurn(requireTask(threadId), input);
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
     *
     * <p>Deletion is blocked when any Task on the thread has already
     * shipped (status {@code COMPLETED}). Shipped tasks have PRs on
     * GitHub and represent real merged work — silently nuking the
     * thread row would orphan that history and lose the audit trail
     * back to the conversation that produced it. The user has to
     * abandon or revert the PR first; until then this returns 409.
     *
     * <p>For threads with no shipped tasks (in-flight, idle, errored,
     * or parked-but-not-merged), deletion is allowed. A live agent
     * is stopped + evicted and any queued turns are cancelled before
     * the row is removed so we never leave a subprocess running
     * against a deleted row.
     *
     * <p>Idempotent on missing ids: a delete-then-delete from a
     * racing tab just returns silently.
     */
    public void delete(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Optional<Thread> existing = store.findThreadById(threadId);
        if (existing.isEmpty()) {
            return;
        }
        // Refuse while any task is still in flight — running / queued
        // tasks hold worktrees and live agent processes, and deleting
        // out from under them would strand both. A zero-task
        // brainstorm thread is fine to drop. Once every task is
        // COMPLETED the user can clean up the thread.
        List<Task> allTasks = taskStore.listTasksByThread(threadId);
        long unfinished = allTasks.stream()
                .filter(t -> t.status() != TaskStatus.COMPLETED)
                .count();
        if (unfinished > 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "Thread " + threadId + " has " + unfinished
                            + " task" + (unfinished == 1 ? "" : "s")
                            + " that haven't completed — finish or stop them"
                            + " before deleting the thread.");
        }
        // Stop the live agent + drop any queued turns so we don't
        // leave a subprocess running against a deleted row.
        Thread thread = existing.get();
        registry.find(threadId).ifPresent(agent -> {
            try {
                agent.stop();
            }
            catch (RuntimeException e) {
                log.warn("agent stop on delete threw for {}: {}", threadId, e.getMessage());
            }
        });
        scheduler.cancelQueuedTurns(threadId);
        registry.evict(threadId);
        // Best-effort worktree cleanup. Errors are logged inside the
        // service; we don't fail the delete if the worktree is already
        // gone or git can't remove it cleanly — the thread row going
        // away is the authoritative signal.
        Task active = thread.activeTask() != null
                ? thread.activeTask()
                : taskStore.findActiveTaskForThread(threadId).orElse(null);
        if (active != null
                && active.worktreePath() != null && !active.worktreePath().isBlank()
                && active.workingDir() != null && !active.workingDir().isBlank()) {
            worktreeService.remove(Path.of(active.workingDir()),
                    active.worktreePath(), active.branchName());
        }
        store.deleteThread(threadId);
    }

    /** Whether the thread is eligible for deletion right now — the
     *  UI uses this to greying out the Delete button and explain why
     *  before the user clicks it. Returns null when allowed, or a
     *  human-readable reason when blocked. */
    public Optional<String> deleteBlockedReason(String threadId)
    {
        if (store.findThreadById(threadId).isEmpty()) {
            return Optional.of("Thread doesn't exist.");
        }
        // Mirror {@link #delete}: a thread is deletable only when every
        // task it owns has reached COMPLETED. Anything else — RUNNING,
        // PENDING, AWAITING, IDLE, AWAITING_REVIEW, NEEDS_ATTENTION,
        // ERRORED — counts as still in flight and blocks the button.
        long unfinished = taskStore.listTasksByThread(threadId).stream()
                .filter(t -> t.status() != TaskStatus.COMPLETED)
                .count();
        if (unfinished > 0) {
            return Optional.of(unfinished + " task" + (unfinished == 1 ? " is" : "s are")
                    + " still in flight — finish or stop them before deleting.");
        }
        return Optional.empty();
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

    private Path agentCwd(Thread thread)
    {
        // Prefer the projected activeTask (set by SqliteThreadStore.toThread)
        // but fall back to a fresh TaskStore lookup. The bridge teardown
        // means thread.activeTask() can be null on Thread records that
        // weren't built via the SQLite store (e.g. some in-memory test
        // doubles); going through TaskStore makes those keep working.
        //
        // The final fallback to findLatestTaskForThread covers the
        // resume-from-terminal path: a COMPLETED thread's latest task
        // is also terminal so findActiveTaskForThread returns empty,
        // but the worktree is still on disk and the diff / commits
        // surfaces should keep working — the user wants to look at
        // what was shipped, not edit it.
        Task active = thread.activeTask();
        if (active == null) {
            active = taskStore.findActiveTaskForThread(thread.id()).orElse(null);
        }
        if (active == null) {
            active = taskStore.findLatestTaskForThread(thread.id()).orElse(null);
        }
        if (active == null || active.agentCwd() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "thread " + thread.id() + " has no task with a working dir");
        }
        return Path.of(active.agentCwd());
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
