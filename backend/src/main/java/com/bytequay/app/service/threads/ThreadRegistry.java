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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Single source of truth for which threads have a live in-memory
 * {@link ThreadAgent}. Controllers go through here to look up,
 * create, or replace sessions; they never instantiate a session
 * directly.
 *
 * <p>Sessions are created lazily. Calling {@link #getOrCreate} for a
 * thread that the registry hasn't seen yet builds a fresh session
 * around its persisted state. This means an app restart followed by
 * a UI poll seamlessly recreates the session map without having to
 * eagerly walk the {@code threads} table at boot.
 *
 * <p>The registry also owns the worktree lease for the lifetime of
 * each session: acquired when {@link #getOrCreate} first builds a
 * session for a thread, released when {@link #evict} drops it. The
 * lease therefore covers the entire human attachment to the thread
 * (a turn is in flight, the user is reading the diff, the user
 * walked away), not just the seconds a CLI subprocess is alive. The
 * automation coordinator's "is the worktree leased?" check is
 * meaningful again, which is what the design doc's "lease is the
 * lock" wording assumes.
 */
@Component
public class ThreadRegistry
{
    private static final Logger log = LoggerFactory.getLogger(ThreadRegistry.class);

    private final ThreadStore store;
    private final TaskStore taskStore;
    private final StreamJsonParser parser;
    private final ObjectMapper mapper;
    private final McpPermissionGate gate;
    private final ExecutorService executor;
    private final CheckpointTrigger checkpointTrigger;
    private final Supplier<String> workspaceMemoryProvider;
    private final WorktreeLeaseService leaseService;
    private final ConcurrentHashMap<String, ThreadAgent> sessions = new ConcurrentHashMap<>();
    /** Worktree path each live session holds the lease against, so
     *  {@link #evict} can release the exact path acquired in
     *  {@link #getOrCreate} even after the underlying task rolled
     *  over. Empty when the thread had no isolated worktree (legacy
     *  0-Task rows). */
    private final ConcurrentHashMap<String, String> leasedWorktrees = new ConcurrentHashMap<>();

    @Autowired
    public ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            McpPermissionGate gate,
            CheckpointTrigger checkpointTrigger,
            WorkspaceService workspaces,
            WorktreeLeaseService leaseService)
    {
        this(store, taskStore, new StreamJsonParser(mapper), mapper, gate,
                ClaudeCodeCliThreadAgent.defaultExecutor(), checkpointTrigger,
                () -> workspaces.getMemory(WorkspaceService.DEFAULT_WORKSPACE_ID),
                leaseService);
    }

    ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            WorktreeLeaseService leaseService)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
    }

    public Optional<ThreadAgent> find(String threadId)
    {
        return Optional.ofNullable(sessions.get(threadId));
    }

    /**
     * Build the session if it isn't already in the map, otherwise
     * return the existing one. The {@link Thread} argument seeds the
     * fresh session's status / metrics, so callers should pass the
     * latest {@link ThreadStore#findThreadById} result.
     *
     * <p>On a fresh attach, the active task's worktree gets a lease
     * tied to the registry-owned session lifetime. A concurrent
     * holder whose process is gone is reclaimed cleanly; a concurrent
     * holder whose process is alive surfaces as 409 so a second
     * agent can't barge in on the same worktree.
     */
    public ThreadAgent getOrCreate(Thread thread)
    {
        requireNonNull(thread, "thread is null");
        ThreadAgent existing = sessions.get(thread.id());
        if (existing != null) {
            return existing;
        }
        // Take the lease BEFORE inserting the agent into the session
        // map. If the lease is held by a live holder, sessions stays
        // unchanged and the caller sees the 409. One TaskStore lookup
        // covers both the "is there a worktree to protect?" check and
        // the acquire's taskId argument.
        Optional<Task> active = taskStore.findActiveTaskForThread(thread.id());
        String leasedPath = active
                .map(Task::worktreePath)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
        if (leasedPath != null) {
            acquireLease(thread.id(), active.get(), leasedPath);
        }
        try {
            return sessions.computeIfAbsent(thread.id(), id -> build(thread));
        }
        catch (RuntimeException e) {
            // Build failed after the lease was acquired; give it back
            // so a retry doesn't keep tripping on our own row.
            if (leasedPath != null) {
                releaseLeaseQuietly(leasedPath);
                leasedWorktrees.remove(thread.id());
            }
            throw e;
        }
    }

    /** Drop a session from the map. The session is not stopped: call
     *  {@link ThreadAgent#stop} first if that matters. Releases the
     *  worktree lease this session was holding so the next attachment
     *  (the user's, an auto-fix run, the next task's session after a
     *  ship-and-continue rollover) sees the worktree as free. */
    public void evict(String threadId)
    {
        sessions.remove(threadId);
        String leased = leasedWorktrees.remove(threadId);
        if (leased != null) {
            releaseLeaseQuietly(leased);
        }
    }

    private void acquireLease(String threadId, Task active, String worktreePath)
    {
        Integer jvmPid = (int) ProcessHandle.current().pid();
        Optional<WorktreeLease> acquired = leaseService.tryAcquireOrReclaim(
                worktreePath, active.id(), ThreadKind.CLI_AGENT, jvmPid);
        if (acquired.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "worktree " + worktreePath + " is leased by another live session");
        }
        leasedWorktrees.put(threadId, worktreePath);
    }

    private void releaseLeaseQuietly(String worktreePath)
    {
        try {
            leaseService.release(worktreePath);
        }
        catch (RuntimeException e) {
            log.warn("Worktree lease release on {} threw: {}", worktreePath, e.getMessage());
        }
    }

    private ThreadAgent build(Thread thread)
    {
        return switch (thread.kind()) {
            case CLI_AGENT -> new ClaudeCodeCliThreadAgent(
                    thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                    workspaceMemoryProvider);
            case LOGIC_LOOP -> throw new UnsupportedOperationException(
                    "LOGIC_LOOP sessions land in a later slice");
        };
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdownNow();
    }
}
