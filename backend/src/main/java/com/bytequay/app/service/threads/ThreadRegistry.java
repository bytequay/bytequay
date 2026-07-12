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

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.local.ds4.Ds4Instrumentation;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.skills.ManagedSkillBundle;
import com.bytequay.app.service.skills.PonytailBundleService;
import com.bytequay.app.service.skills.RoleSkillService;
import com.bytequay.app.service.skills.SkillMaterializer;
import com.bytequay.app.service.stage.AgentContextDigest;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Single source of truth for which threads have a live in-memory
 * {@link Agent}. Controllers go through here to look up,
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
    private static final String PLANNING_REASONING_EFFORT = "high";

    private final ThreadStore store;
    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final StreamJsonParser parser;
    /** Codex's JSONL stdout parser, derived from the same mapper. The
     *  {@code codex} work model dispatches to it instead of {@link
     *  #parser}. */
    private final CodexJsonParser codexParser;
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
    private final PonytailBundleService ponytailBundleService;
    /** Resolves the effective work model for the API lane; null on
     *  CLI-only legacy / test paths. */
    private final WorkModelResolver workModelResolver;
    /** Authenticates API-lane turns; null on CLI-only paths. */
    private final CredentialService credentialService;
    /** Tools the API-lane loop exposes to the model. Null on legacy
     *  test paths; production wires the Spring-discovered list. */
    private final LogicLoopToolRegistry toolRegistry;
    /** Local ds4 supervisor — the LogicLoopThreadAgent reads its
     *  live endpoint when the resolved work model is
     *  deepseek-v4-flash. Null on tests. */
    private final Ds4LifecycleService ds4;
    /** Local ds4 metrics ring — thread turns recorded here show up
     *  on the Settings → Local AI (ds4) → Metrics tab alongside
     *  review-path calls. Null on tests. */
    private final Ds4Instrumentation ds4Instrumentation;
    /** Builds the brain agent's iteration-summary context digest at
     *  session creation. Null on legacy/test paths that never build a
     *  brain agent. */
    private final AgentContextDigest contextDigest;
    private final CodeGraphUpdateCoordinator codeGraph;
    private final WorktreeLeaseService leaseService;
    /** Resolves the cwd a trunk session should be spawned in. Takes
     *  the Thread because the trunk's working dir is workspace-
     *  scoped: it must be one of the active workspace's pinned
     *  repos, never an arbitrary watched repo from another workspace. */
    private final Function<Thread, String> trunkCwdResolver;
    /** Live dev/CLI sessions keyed by <em>stage key</em>, not threadId:
     *  each stage of a task (Development, CI-fixing, Comments-addressing)
     *  gets its own fresh agent. The stage key is the stage id when the
     *  turn is stage-scoped, the task id for a task-level turn with no
     *  stage, and the thread id on a legacy 0-stage path. A trunk thread's
     *  many concurrent tasks therefore map to many concurrent entries. */
    private final ConcurrentHashMap<String, ThreadAgent> sessions = new ConcurrentHashMap<>();
    /** Secondary index: threadId → the set of stage keys it currently has
     *  a live session for. Lets {@link #find}/{@link #evict} reach every
     *  stage-agent on a thread without scanning {@link #sessions}. */
    private final ConcurrentHashMap<String, Set<String>> threadStageKeys = new ConcurrentHashMap<>();
    /** Per-thread trunk-mode agent — the planning-altitude runtime
     *  that runs without a focused Task. Lives alongside (not instead
     *  of) the task-scope {@link #sessions} so switching trunk ↔ task
     *  inside one Thread doesn't tear down either session. */
    private final ConcurrentHashMap<String, ThreadAgent> trunkSessions = new ConcurrentHashMap<>();
    /** Worktree path each live session holds the lease against, keyed by
     *  the same stage key as {@link #sessions}, so {@link #evict} can
     *  release the exact path acquired in {@link #getOrCreate} even after
     *  the underlying task rolled over. Empty when the stage had no
     *  isolated worktree (legacy 0-Task rows). */
    private final ConcurrentHashMap<String, String> leasedWorktrees = new ConcurrentHashMap<>();

    @Autowired
    public ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StageStore stageStore,
            ObjectMapper mapper,
            McpPermissionGate gate,
            CheckpointTrigger checkpointTrigger,
            WorkspaceService workspaces,
            WorktreeLeaseService leaseService,
            WatchedRepoStore watchedRepos,
            WorktreeService worktreeService,
            SkillMaterializer skillMaterializer,
            RoleSkillService roleSkillService,
            PonytailBundleService ponytailBundleService,
            WorkModelResolver workModelResolver,
            CredentialService credentialService,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            AgentContextDigest contextDigest,
            CodeGraphUpdateCoordinator codeGraph)
    {
        this(store, taskStore, stageStore, new StreamJsonParser(mapper), mapper, gate,
                ClaudeCodeCliThreadAgent.defaultExecutor(), checkpointTrigger,
                () -> workspaces.getMemory(WorkspaceService.DEFAULT_WORKSPACE_ID),
                leaseService,
                // The trunk runs in a read-only planning worktree pinned to the
                // up-to-date base (upstream/master for a fork, origin/main for a
                // direct clone), not the user's arbitrary checkout. Resolving the
                // cwd also fetches + resets that worktree; the CLI trunk session
                // re-runs it per turn via setPreTurnHook below.
                thread -> resolveTrunkPlanningCwd(worktreeService, workspaces, watchedRepos, thread),
                skillMaterializer,
                roleSkillService,
                ponytailBundleService,
                workModelResolver,
                credentialService,
                toolRegistry,
                ds4,
                ds4Instrumentation,
                contextDigest,
                codeGraph);
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
                        .map(WorkspaceRepo::repoFullName)
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

    /**
     * Trunk cwd resolver that anchors planning to the base branch: resolve
     * the clone root as before, then fetch + checkout a read-only planning
     * worktree at the up-to-date base ref and run the trunk there. Falls
     * back to the clone root when no planning worktree can be cut (no git,
     * unresolvable base) so planning still launches.
     */
    private static String resolveTrunkPlanningCwd(
            WorktreeService worktreeService,
            WorkspaceService workspaces,
            WatchedRepoStore watchedRepos,
            Thread thread)
    {
        String cloneRoot = resolveTrunkCwdForWorkspace(workspaces, watchedRepos, thread);
        return worktreeService.ensurePlanningWorktree(Path.of(cloneRoot))
                .map(sync -> sync.worktree().toString())
                .orElse(cloneRoot);
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
        this(store, taskStore, null, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, leaseService,
                thread -> System.getProperty("java.io.tmpdir"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CodeGraphUpdateCoordinator.disabled());
    }

    ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StageStore stageStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            WorktreeLeaseService leaseService,
            Function<Thread, String> trunkCwdResolver,
            SkillMaterializer skillMaterializer,
            RoleSkillService roleSkillService,
            PonytailBundleService ponytailBundleService,
            WorkModelResolver workModelResolver,
            CredentialService credentialService,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            AgentContextDigest contextDigest,
            CodeGraphUpdateCoordinator codeGraph)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = stageStore;
        this.parser = requireNonNull(parser, "parser is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.codexParser = new CodexJsonParser(mapper);
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
        this.trunkCwdResolver = requireNonNull(trunkCwdResolver, "trunkCwdResolver is null");
        this.skillMaterializer = skillMaterializer;
        this.roleSkillService = roleSkillService;
        this.ponytailBundleService = ponytailBundleService;
        this.workModelResolver = workModelResolver;
        this.credentialService = credentialService;
        this.toolRegistry = toolRegistry;
        this.ds4 = ds4;
        this.ds4Instrumentation = ds4Instrumentation;
        this.contextDigest = contextDigest;
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
    }

    ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StageStore stageStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            WorktreeLeaseService leaseService,
            Function<Thread, String> trunkCwdResolver,
            SkillMaterializer skillMaterializer,
            RoleSkillService roleSkillService,
            PonytailBundleService ponytailBundleService,
            WorkModelResolver workModelResolver,
            CredentialService credentialService,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            AgentContextDigest contextDigest)
    {
        this(store, taskStore, stageStore, parser, mapper, gate, executor,
                checkpointTrigger, workspaceMemoryProvider, leaseService,
                trunkCwdResolver, skillMaterializer, roleSkillService,
                ponytailBundleService, workModelResolver, credentialService,
                toolRegistry, ds4, ds4Instrumentation, contextDigest,
                CodeGraphUpdateCoordinator.disabled());
    }

    /**
     * A live stage-agent for this thread, preferring one whose turn is
     * still RUNNING (the one an interrupt should hit). Returns empty when
     * the thread has no live stage-agent. Use {@link #findAll} when every
     * stage-agent matters (evict / stop).
     */
    public Optional<ThreadAgent> find(String threadId)
    {
        List<ThreadAgent> all = findAll(threadId);
        return all.stream()
                .filter(a -> a.status() == ThreadStatus.RUNNING)
                .findFirst()
                .or(() -> all.stream().findFirst());
    }

    /** The live stage-agent for a stage key (= stage id for a stage-
     *  scoped agent), if one exists. */
    public Optional<ThreadAgent> findStage(String stageKey)
    {
        return Optional.ofNullable(stageKey == null ? null : sessions.get(stageKey));
    }

    /** Every live stage-agent for this thread (zero, one, or — with
     *  concurrent stages — several). */
    public List<ThreadAgent> findAll(String threadId)
    {
        Set<String> keys = threadStageKeys.get(threadId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<ThreadAgent> out = new ArrayList<>();
        for (String key : Set.copyOf(keys)) {
            ThreadAgent agent = sessions.get(key);
            if (agent != null) {
                out.add(agent);
            }
        }
        return out;
    }

    /** The exact stage-agent filed under {@code agentKey} (the per-agent
     *  MCP key, == the stage key it was registered with). Lets a permission
     *  prompt / decision route back to the precise agent that raised it
     *  rather than a thread-level "active session" guess. Empty when the key
     *  names a trunk agent or an evicted session. */
    public Optional<ThreadAgent> findByAgentKey(String agentKey)
    {
        return agentKey == null || agentKey.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(sessions.get(agentKey));
    }

    /** Trunk-scope counterpart of {@link #find(String)} — the
     *  planning-altitude runtime, present only when the user has
     *  driven at least one turn on the trunk in this JVM. */
    public Optional<ThreadAgent> findTrunk(String threadId)
    {
        return Optional.ofNullable(trunkSessions.get(threadId));
    }

    public Optional<TrunkAgent> findTrunkAgent(String threadId)
    {
        return findTrunk(threadId).map(AgentViews::trunk);
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

    public TrunkAgent getOrCreateTrunkAgent(Thread thread)
    {
        return AgentViews.trunk(getOrCreateTrunk(thread));
    }

    public TaskBrainAgent getOrCreateTaskBrainAgent(Thread thread)
    {
        requireNonNull(thread, "thread is null");
        if (thread.kind() != ThreadKind.BRAIN_AGENT) {
            throw new IllegalArgumentException("thread " + thread.id() + " is not a task brain thread");
        }
        return AgentViews.taskBrain(getOrCreateTrunk(thread));
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
        // Legacy entry point: resolve the active-or-latest task and its
        // active stage, then route through the per-stage path. Kept so
        // callers that haven't yet threaded the running turn's stage id
        // keep working with the same behaviour.
        Task task = taskStore.activeTasksForThread(thread.id()).stream().findFirst()
                .or(() -> taskStore.findLatestTaskForThread(thread.id()))
                .orElse(null);
        return getOrCreate(thread, task, null);
    }

    /**
     * Build (or return) the per-stage CLI agent for {@code stageId}. Each
     * stage of a task — Development, CI-fixing, Comments-addressing — gets
     * its own fresh agent, so a trunk thread can drive several stages
     * concurrently. The session is keyed by the stage key derived from
     * ({@code stageId}, {@code task}, {@code thread}), and the worktree
     * lease is taken against that same key so two live stage-agents never
     * share one worktree.
     *
     * @param task the task this stage belongs to; null only on the legacy
     *             0-task trunk-ish path, which never reaches a worktree.
     * @param stageId the stamped stage id of the running turn; null for a
     *             task-level turn with no stage (keys by task id) or a
     *             0-task path (keys by thread id).
     */
    public ThreadAgent getOrCreate(Thread thread, Task task, String stageId)
    {
        requireNonNull(thread, "thread is null");
        requireAgentBackedStage(stageId);
        String key = stageKey(thread.id(), task, stageId);
        ThreadAgent existing = sessions.get(key);
        if (existing != null) {
            return existing;
        }
        // Take the lease BEFORE inserting the agent into the session map.
        // If the lease is held by a live holder, sessions stays unchanged
        // and the caller sees the 409.
        //
        // The lease is a WRITE lock. Read-only sessions (the brain's planning
        // + conversational agent, on a BRAIN_AGENT thread) never write the
        // worktree, so they must not take it — otherwise an idle brain session
        // keeps the lock and the dev/CI-fix turn for the SAME task 409s on it
        // (both share this JVM's pid, so reclaim-on-dead-pid can't free it).
        // Only writing stages (dev / CI-fix / cleanup, on CLI_AGENT threads)
        // lease the worktree.
        boolean writesWorktree = thread.kind() != ThreadKind.BRAIN_AGENT;
        String leasedPath = task == null || !writesWorktree
                ? null
                : Optional.ofNullable(task.worktreePath())
                        .filter(p -> !p.isBlank())
                        .orElse(null);
        if (leasedPath != null) {
            acquireLease(key, task, leasedPath);
        }
        try {
            ThreadAgent agent = sessions.computeIfAbsent(key, k -> {
                ThreadAgent built = buildStage(thread, task, stageId);
                // Bind the agent to the stage key it's filed under so its CLI
                // subprocess writes a per-agent MCP URL and tool calls resolve
                // role / capability against its own running turn.
                built.setMcpAgentKey(key);
                return built;
            });
            threadStageKeys.computeIfAbsent(thread.id(), id -> ConcurrentHashMap.newKeySet()).add(key);
            return agent;
        }
        catch (RuntimeException e) {
            // Build failed after the lease was acquired; give it back
            // so a retry doesn't keep tripping on our own row.
            if (leasedPath != null) {
                releaseLeaseQuietly(leasedPath);
                leasedWorktrees.remove(key);
            }
            throw e;
        }
    }

    public StageAgent getOrCreateStageAgent(Thread thread, Task task, String stageId)
    {
        return AgentViews.stage(getOrCreate(thread, task, stageId));
    }

    /** Evict and release every stage-agent for the given thread. The
     *  sessions are not stopped: call {@link ThreadAgent#stop} first if
     *  that matters. Releases each worktree lease so the next attachment
     *  sees the worktree as free. */
    public void evict(String threadId)
    {
        Set<String> keys = threadStageKeys.remove(threadId);
        if (keys == null) {
            // Legacy 0-stage callers may have keyed directly by threadId.
            keys = Set.of(threadId);
        }
        for (String key : keys) {
            sessions.remove(key);
            String leased = leasedWorktrees.remove(key);
            if (leased != null) {
                releaseLeaseQuietly(leased);
            }
        }
    }

    /** The live agents for a set of stage keys belonging to one thread —
     *  e.g. every stage of a single task. Used to interrupt/stop the
     *  agents of one task without touching the thread's other tasks. */
    public List<ThreadAgent> findStages(Collection<String> stageKeys)
    {
        if (stageKeys == null || stageKeys.isEmpty()) {
            return List.of();
        }
        List<ThreadAgent> out = new ArrayList<>();
        for (String key : stageKeys) {
            ThreadAgent agent = key == null ? null : sessions.get(key);
            if (agent != null) {
                out.add(agent);
            }
        }
        return out;
    }

    /** Evict + release every stage-agent in {@code stageKeys} for the
     *  given thread. Targets one task's stages without disturbing the
     *  thread's other concurrent tasks. */
    public void evictStages(String threadId, Collection<String> stageKeys)
    {
        if (stageKeys == null) {
            return;
        }
        for (String key : stageKeys) {
            if (key != null) {
                evictStage(threadId, key);
            }
        }
    }

    /** Evict and release a single stage-agent by its stage key (= stage
     *  id for a stage-scoped agent). Used when a stage closes so its CLI
     *  process + worktree lease are released without touching the thread's
     *  other concurrent stages. */
    public void evictStage(String threadId, String stageKey)
    {
        requireNonNull(stageKey, "stageKey is null");
        sessions.remove(stageKey);
        String leased = leasedWorktrees.remove(stageKey);
        if (leased != null) {
            releaseLeaseQuietly(leased);
        }
        Set<String> keys = threadStageKeys.get(threadId);
        if (keys != null) {
            keys.remove(stageKey);
            if (keys.isEmpty()) {
                threadStageKeys.remove(threadId, keys);
            }
        }
    }

    /** The find/evict key for a stage-agent: the stage id when stage-
     *  scoped, else the task id for a task-level turn, else the thread id
     *  on the legacy 0-task path. */
    private static String stageKey(String threadId, Task task, String stageId)
    {
        if (stageId != null && !stageId.isBlank()) {
            return stageId;
        }
        if (task != null && task.id() != null && !task.id().isBlank()) {
            return task.id();
        }
        return threadId;
    }

    private void acquireLease(String stageKey, Task active, String worktreePath)
    {
        Integer jvmPid = (int) ProcessHandle.current().pid();
        Optional<WorktreeLease> acquired = leaseService.tryAcquireOrReclaim(
                worktreePath, active.id(), ThreadKind.CLI_AGENT, jvmPid);
        if (acquired.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "worktree " + worktreePath + " is leased by another live session");
        }
        leasedWorktrees.put(stageKey, worktreePath);
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

    private ThreadAgent buildStage(Thread thread, Task boundTask, String stageId)
    {
        // The CLI agent binds to the explicit task the caller resolved for
        // this stage, rather than re-deriving it inside the agent ctor.
        // Resolved once via the full stage → task → thread → workspace →
        // global cascade — a stage's session is built once and reused
        // across every iteration within it (see getOrCreate), so this is a
        // stage-open-time decision, not re-evaluated per turn.
        WorkModel resolved = resolveWorkModelForStage(thread, boundTask, stageId);
        ThreadAgent agent = switch (thread.kind()) {
            case CLI_AGENT -> isCodex(thread)
                    ? new CodexCliThreadAgent(
                            thread, store, taskStore, codexParser, mapper, gate, executor, checkpointTrigger,
                            workspaceMemoryProvider,
                            resolveTaskRoleSkill(boundTask),
                            boundTask, cliModelOverride(resolved))
                    : new ClaudeCodeCliThreadAgent(
                            thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                            workspaceMemoryProvider, skillMaterializer,
                            resolveTaskRoleSkill(boundTask),
                            boundTask, cliModelOverride(resolved));
            case LOGIC_LOOP -> {
                String workingDir = boundTask != null
                        ? boundTask.workingDir()
                        : trunkCwdResolver.apply(thread);
                yield new LogicLoopThreadAgent(
                        thread, store, mapper, executor,
                        credentialService, resolved, workingDir,
                        resolveTaskRoleSkill(boundTask), toolRegistry,
                        ds4, ds4Instrumentation, gate);
            }
            case BRAIN_AGENT -> buildBrain(thread);
        };
        if (agent instanceof AbstractCliThreadAgent cli && boundTask != null) {
            Optional<Path> checkout = taskCheckout(boundTask);
            checkout.ifPresent(path -> {
                cli.setPreTurnHook(() -> codeGraph.ensureFreshSync(path, "before-agent-turn"));
                cli.setPostTurnHook(() -> codeGraph.requestRefreshAsync(path, "after-agent-turn"));
            });
        }
        return withManagedSkillBundle(agent);
    }

    private static Optional<Path> taskCheckout(Task task)
    {
        return Optional.ofNullable(task.worktreePath())
                .filter(p -> !p.isBlank())
                .or(() -> Optional.ofNullable(task.workingDir()).filter(p -> !p.isBlank()))
                .map(Path::of);
    }

    /** The resolved cascade's model id, but only when it's actually a CLI
     *  choice — a resolution that came out API-kind for a CLI_AGENT thread
     *  means the override is inconsistent with the thread's own kind, and
     *  passing an API model id as {@code --model}/{@code -m} would be
     *  wrong, so this falls back to null (the CLI's own default) instead. */
    private static String cliModelOverride(WorkModel resolved)
    {
        return resolved.kind() == WorkModelKind.CLI ? resolved.model() : null;
    }

    /** Resolves the effective work model for a stage's spawn: stage → task
     *  → thread → workspace → global default. Falls back to the thread-only
     *  cascade on the legacy 0-task path (no bound task) or when the
     *  resolver isn't wired (test paths). */
    private WorkModel resolveWorkModelForStage(Thread thread, Task boundTask, String stageId)
    {
        if (boundTask == null || workModelResolver == null) {
            return resolveWorkModel(thread.id());
        }
        if (stageId != null && !stageId.isBlank()) {
            return workModelResolver.resolveForStage(thread.id(), boundTask.id(), stageId).choice();
        }
        return workModelResolver.resolveForTask(thread.id(), boundTask.id()).choice();
    }

    private ThreadAgent buildTrunk(Thread thread)
    {
        return switch (thread.kind()) {
            case CLI_AGENT -> {
                // trunkCwdResolver also fetches + resets the planning worktree
                // (the cwd). Re-run it before every turn so each planning turn
                // searches an up-to-date base.
                String initialCwd = trunkCwdResolver.apply(thread);
                AbstractCliThreadAgent agent = isCodex(thread)
                        ? new CodexCliThreadAgent(
                                thread, store, taskStore, codexParser, mapper, gate, executor, checkpointTrigger,
                                workspaceMemoryProvider,
                                roleSkillService == null ? null : roleSkillService.trunkTemplate(),
                                initialCwd,
                                CodexCliThreadAgent.TrunkMode.ENABLED,
                                PLANNING_REASONING_EFFORT)
                        : new ClaudeCodeCliThreadAgent(
                                thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                                workspaceMemoryProvider, skillMaterializer,
                                roleSkillService == null ? null : roleSkillService.trunkTemplate(),
                                initialCwd,
                                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED,
                                PLANNING_REASONING_EFFORT);
                agent.setPreTurnHook(() -> {
                    String synced = trunkCwdResolver.apply(thread);
                    log.debug("trunk {} planning base synced at {}", thread.id(), synced);
                });
                agent.setPostTurnHook(() -> codeGraph.requestRefreshAsync(Path.of(initialCwd), "after-trunk-turn"));
                yield withManagedSkillBundle(agent);
            }
            case LOGIC_LOOP -> {
                WorkModel resolved = resolveWorkModel(thread.id());
                yield withManagedSkillBundle(new LogicLoopThreadAgent(
                        thread, store, mapper, executor,
                        credentialService, resolved, trunkCwdResolver.apply(thread),
                        roleSkillService == null ? null : roleSkillService.trunkTemplate(),
                        toolRegistry, ds4, ds4Instrumentation, gate));
            }
            // Brain turns carry no task id in the turn row, but the thread
            // kind still builds the task-brain runtime, not a trunk planner.
            case BRAIN_AGENT -> buildBrain(thread);
        };
    }

    /**
     * Build the brain agent for a brain thread, following its resolved work
     * model: a claude-code CLI subprocess on a CLI install, or the in-JVM
     * {@link LogicLoopThreadAgent} on an API install. Either way its system
     * prompt is the read-only brain template + the task's iteration digest,
     * and its working directory is the parent task's so file/git read tools
     * resolve against the right clone. (Codex-as-brain and per-provider API
     * keys are a follow-up; CLI here means claude-code.)
     */
    private ThreadAgent buildBrain(Thread thread)
    {
        WorkModel resolved = resolveWorkModel(thread.id());
        String workingDir = thread.parentTaskId() == null
                ? null
                : taskStore.findTaskById(thread.parentTaskId())
                        .map(Task::workingDir)
                        .orElse(null);
        if (resolved.kind() == WorkModelKind.CLI) {
            // Runs without a focused Task in the parent task's worktree, with
            // the brain prompt as its role block. Its tool surface is scoped
            // to the brain allowlist by the MCP server (ThreadKind=BRAIN_AGENT).
            return withManagedSkillBundle(new ClaudeCodeCliThreadAgent(
                    thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                    workspaceMemoryProvider, skillMaterializer,
                    brainSystemPrompt(thread), workingDir,
                    ClaudeCodeCliThreadAgent.TrunkMode.ENABLED,
                    PLANNING_REASONING_EFFORT));
        }
        return withManagedSkillBundle(new LogicLoopThreadAgent(
                thread, store, mapper, executor,
                credentialService, resolved, workingDir,
                brainSystemPrompt(thread), toolRegistry,
                ds4, ds4Instrumentation, gate));
    }

    /**
     * The brain agent's full system prompt: the read-only role template
     * followed by a digest of the parent task's recent iteration summaries
     * (the cross-agent context preload). Composed at session-creation time
     * and passed as the agent's role-skill text. Falls back to the bare
     * template when no digest service or parent task is available.
     */
    private String brainSystemPrompt(Thread thread)
    {
        if (contextDigest == null || thread.parentTaskId() == null) {
            return LogicLoopThreadAgent.BRAIN_SYSTEM_PROMPT;
        }
        String digest = contextDigest.build(
                thread.parentTaskId(), AgentContextDigest.DEFAULT_CAP_TOKENS);
        return LogicLoopThreadAgent.BRAIN_SYSTEM_PROMPT + "\n\n" + digest;
    }

    /** Whether a CLI-agent thread should run the {@code codex} binary
     *  rather than {@code claude}. Keyed on the provider stored at thread
     *  creation ({@code "codex"} vs {@code "claude-code"}), so it doesn't
     *  need the work-model resolver. */
    private static boolean isCodex(Thread thread)
    {
        return "codex".equals(thread.provider());
    }

    private WorkModel resolveWorkModel(String threadId)
    {
        if (workModelResolver != null) {
            return workModelResolver.resolveForThread(threadId).choice();
        }
        // Fallback for test paths where the resolver isn't wired.
        return new WorkModel(WorkModelKind.API, "anthropic", null, null);
    }

    /** The bound task's frozen role skill body for the CLI agent's
     *  session role block, or null when no task is bound. */
    private static String resolveTaskRoleSkill(Task boundTask)
    {
        return boundTask == null ? null : boundTask.roleSkill();
    }

    private ThreadAgent withManagedSkillBundle(ThreadAgent agent)
    {
        ManagedSkillBundle bundle = ponytailBundleService == null
                ? ManagedSkillBundle.empty()
                : ponytailBundleService.snapshot();
        agent.setManagedSkillBundle(bundle);
        return agent;
    }

    private void requireAgentBackedStage(String stageId)
    {
        agentStageType(stageId).ifPresent(type -> {
            if (type == StageType.PLAN_STAGE) {
                throw new IllegalArgumentException("PlanStage is backed by TaskBrainAgent");
            }
            if (type == StageType.CLEANUP_STAGE) {
                throw new IllegalArgumentException("CleanupStage does not have an agent runtime");
            }
        });
    }

    private Optional<StageType> agentStageType(String stageId)
    {
        if (stageStore == null || stageId == null || stageId.isBlank()) {
            return Optional.empty();
        }
        try {
            return stageStore.findStageById(UUID.fromString(stageId)).map(stage -> stage.type());
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdownNow();
    }
}
