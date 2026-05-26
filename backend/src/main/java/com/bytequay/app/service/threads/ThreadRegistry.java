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
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.skills.RoleSkillService;
import com.bytequay.app.service.skills.SkillMaterializer;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    /** Resolves the skills the CLI lane materializes for each session.
     *  May be null on legacy / test paths that don't care about skill
     *  materialization. */
    private final SkillMaterializer skillMaterializer;
    /** Resolves the trunk role skill template. Task role skills come
     *  from each task's frozen {@code role_skill} column. May be null
     *  on legacy / test paths. */
    private final RoleSkillService roleSkillService;
    private final WorktreeLeaseService leaseService;
    /** Resolves the cwd a trunk session should be spawned in. Takes
     *  the Thread because the trunk's working dir is workspace-
     *  scoped: it must be one of the active workspace's pinned
     *  repos, never an arbitrary watched repo from another workspace. */
    private final Function<Thread, String> trunkCwdResolver;
    private final ConcurrentHashMap<String, ThreadAgent> sessions = new ConcurrentHashMap<>();
    /** Per-thread trunk-mode agent — the planning-altitude runtime
     *  that runs without a focused Task. Lives alongside (not instead
     *  of) the task-scope {@link #sessions} so switching trunk ↔ task
     *  inside one Thread doesn't tear down either session. */
    private final ConcurrentHashMap<String, ThreadAgent> trunkSessions = new ConcurrentHashMap<>();
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
            WorktreeLeaseService leaseService,
            WatchedRepoStore watchedRepos,
            SkillMaterializer skillMaterializer,
            RoleSkillService roleSkillService)
    {
        this(store, taskStore, new StreamJsonParser(mapper), mapper, gate,
                ClaudeCodeCliThreadAgent.defaultExecutor(), checkpointTrigger,
                () -> workspaces.getMemory(WorkspaceService.DEFAULT_WORKSPACE_ID),
                leaseService,
                thread -> resolveTrunkCwdForWorkspace(workspaces, watchedRepos, thread),
                skillMaterializer,
                roleSkillService);
    }

    /**
     * Resolve the cwd a trunk planning session should run in. The
     * trunk has no worktree of its own, but the CLI still needs a
     * working directory — we want it to be one of the active
     * workspace's pinned repos so a thread in workspace X doesn't
     * end up rooted in a repo from workspace Y.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If the thread carries a workspaceId, walk its pinned
     *       {@code workspace_repos}, match each by {@code
     *       repo_full_name = owner/repo} against {@code watched_repos},
     *       and return the first non-blank {@code localClonePath}.</li>
     *   <li>If no pinned repo has a local clone path, fall back to
     *       the first watched repo with a local clone path (legacy
     *       single-workspace behaviour).</li>
     *   <li>Last resort: the JVM tmpdir so the CLI still launches.</li>
     * </ol>
     */
    private static String resolveTrunkCwdForWorkspace(
            WorkspaceService workspaces, WatchedRepoStore watchedRepos, Thread thread)
    {
        String workspaceId = thread == null ? null : thread.workspaceId();
        if (workspaceId != null && !workspaceId.isBlank()) {
            try {
                Set<String> pinned = workspaces.listRepos(workspaceId).stream()
                        .map(r -> r.repoFullName())
                        .collect(Collectors.toSet());
                if (!pinned.isEmpty()) {
                    Optional<String> match = watchedRepos.findAll().stream()
                            .filter(wr -> pinned.contains(wr.fullName()))
                            .map(WatchedRepo::localClonePath)
                            .filter(p -> p != null && !p.isBlank())
                            .findFirst();
                    if (match.isPresent()) {
                        return match.get();
                    }
                }
            }
            catch (RuntimeException ignored) {
                // Workspace lookup failed (deleted mid-session?) — drop
                // to the legacy fallback rather than aborting the turn.
            }
        }
        return watchedRepos.findAll().stream()
                .map(WatchedRepo::localClonePath)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElseGet(() -> System.getProperty("java.io.tmpdir"));
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
        this(store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, leaseService,
                thread -> System.getProperty("java.io.tmpdir"),
                null,
                null);
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
            WorktreeLeaseService leaseService,
            Function<Thread, String> trunkCwdResolver,
            SkillMaterializer skillMaterializer,
            RoleSkillService roleSkillService)
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
        this.trunkCwdResolver = requireNonNull(trunkCwdResolver, "trunkCwdResolver is null");
        this.skillMaterializer = skillMaterializer;
        this.roleSkillService = roleSkillService;
    }

    public Optional<ThreadAgent> find(String threadId)
    {
        return Optional.ofNullable(sessions.get(threadId));
    }

    /** Trunk-scope counterpart of {@link #find(String)} — the
     *  planning-altitude runtime, present only when the user has
     *  driven at least one turn on the trunk in this JVM. */
    public Optional<ThreadAgent> findTrunk(String threadId)
    {
        return Optional.ofNullable(trunkSessions.get(threadId));
    }

    /**
     * Build (or return) the trunk-scope agent for this thread. Unlike
     * {@link #getOrCreate} no worktree lease is acquired — the trunk
     * holds no branch and no worktree of its own. The cwd is the
     * first watched-repo clone path so the CLI can still read files.
     */
    public ThreadAgent getOrCreateTrunk(Thread thread)
    {
        requireNonNull(thread, "thread is null");
        return trunkSessions.computeIfAbsent(thread.id(), id -> buildTrunk(thread));
    }

    public void evictTrunk(String threadId)
    {
        trunkSessions.remove(threadId);
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
                    workspaceMemoryProvider, skillMaterializer,
                    resolveTaskRoleSkill(thread));
            case LOGIC_LOOP -> throw new UnsupportedOperationException(
                    "LOGIC_LOOP sessions land in a later slice");
        };
    }

    private ThreadAgent buildTrunk(Thread thread)
    {
        return switch (thread.kind()) {
            case CLI_AGENT -> new ClaudeCodeCliThreadAgent(
                    thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                    workspaceMemoryProvider, skillMaterializer,
                    roleSkillService == null ? null : roleSkillService.trunkTemplate(),
                    trunkCwdResolver.apply(thread),
                    ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);
            case LOGIC_LOOP -> throw new UnsupportedOperationException(
                    "LOGIC_LOOP trunk sessions land in a later slice");
        };
    }

    /** Resolve the active task's frozen role skill body for the CLI
     *  agent's session role block. The active lookup mirrors the
     *  agent's own resolution so we hand it the same row. */
    private String resolveTaskRoleSkill(Thread thread)
    {
        return taskStore.findActiveTaskForThread(thread.id())
                .or(() -> taskStore.findLatestTaskForThread(thread.id()))
                .map(Task::roleSkill)
                .orElse(null);
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdownNow();
    }
}
