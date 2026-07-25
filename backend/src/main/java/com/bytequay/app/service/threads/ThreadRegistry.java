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
import com.bytequay.app.domain.ThreadFlow;
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
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.skills.SkillMaterializer;
import com.bytequay.app.service.stage.AgentContextDigest;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
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
import java.util.stream.Stream;

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
    private final Function<Thread, String> workspaceMemoryProvider;
    /** Production-only audience-filtered brain + KB read path. Legacy tests
     *  keep using workspaceMemoryProvider directly. */
    private SessionKnowledgeProvider sessionKnowledge;
    /** Supplies ByteQuay-managed skill bodies to every provider lane. */
    private final SkillMaterializer skillMaterializer;
    /** Resolves ByteQuay role definitions and legacy task role values. */
    private final RoleRegistry roleRegistry;
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
    /** Live dev/CLI sessions keyed by thread + <em>stage key</em>:
     *  each stage of a task (Development, CI-fixing, Comments-addressing)
     *  gets its own fresh agent. The stage key is the stage id when the
     *  turn is stage-scoped, the task id for a task-level turn with no
     *  stage, and the thread id on a legacy 0-stage path. Thread identity
     *  matters because a brain-review turn and its development fix turn
     *  deliberately share the same task stage id. */
    private final ConcurrentHashMap<SessionKey, ThreadAgent> sessions = new ConcurrentHashMap<>();
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
     *  the same thread + stage key as {@link #sessions}, so {@link #evict} can
     *  release the exact path acquired in {@link #getOrCreate} even after
     *  the underlying task rolled over. Empty when the stage had no
     *  isolated worktree (legacy 0-Task rows). */
    private final ConcurrentHashMap<SessionKey, String> leasedWorktrees = new ConcurrentHashMap<>();

    @Autowired
    public ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StageStore stageStore,
            ObjectMapper mapper,
            McpPermissionGate gate,
            CheckpointTrigger checkpointTrigger,
            WorkspaceService workspaces,
            SessionKnowledgeProvider sessionKnowledge,
            WorktreeLeaseService leaseService,
            WatchedRepoStore watchedRepos,
            WorktreeService worktreeService,
            SkillMaterializer skillMaterializer,
            RoleRegistry roleRegistry,
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
                thread -> workspaceMemory(workspaces, thread),
                leaseService,
                // The trunk runs in its own detached planning worktree. Every
                // turn starts from the freshest locally-known base — background
                // fetches keep those refs current, so no turn touches the network.
                thread -> resolveTrunkPlanningCwd(
                        store, worktreeService, workspaces, watchedRepos, thread),
                skillMaterializer,
                roleRegistry,
                ponytailBundleService,
                workModelResolver,
                credentialService,
                toolRegistry,
                ds4,
                ds4Instrumentation,
                contextDigest,
                codeGraph);
        this.sessionKnowledge = requireNonNull(
                sessionKnowledge, "sessionKnowledge is null");
    }

    /**
     * Resolve the cwd a trunk planning session should run in. The
     * trunk has no worktree of its own, but the CLI still needs a
     * working directory — we want it to be one of the active
     * workspace's pinned repos so a thread in workspace X doesn't
     * end up rooted in a repo from workspace Y.
     *
     * <p>A trunk conversation is local-workspace work. It must have exactly
     * one workspace repo with a verified local clone; never borrow another
     * workspace's clone or launch the CLI from a temporary directory.
     */
    private static String resolveTrunkCwdForWorkspace(
            WorkspaceService workspaces, WatchedRepoStore watchedRepos, Thread thread)
    {
        String workspaceId = thread == null ? null : thread.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalStateException("a local agent thread requires a workspace");
        }
        List<WorkspaceRepo> repos = workspaces.listRepos(workspaceId);
        if (repos.size() != 1) {
            throw new IllegalStateException("workspace " + workspaceId
                    + " must be bound to exactly one repository");
        }
        String repo = repos.getFirst().repoFullName();
        return watchedRepos.findAll().stream()
                .filter(watched -> repo.equalsIgnoreCase(watched.fullName()))
                .map(WatchedRepo::localClonePath)
                .filter(ThreadRegistry::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace " + workspaceId
                        + " has no verified local clone for " + repo));
    }

    private static boolean isDirectory(String path)
    {
        try {
            return path != null && !path.isBlank() && Files.isDirectory(Path.of(path));
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Trunk cwd resolver that anchors planning to the base branch: resolve
     * the clone root, then sync the thread's read-only planning worktree to
     * the freshest locally-known base ref and run the trunk there. The sync
     * is all-local (background fetches keep the refs current); the snapshot
     * row is rewritten only when the base actually moved, so an unchanged
     * base doesn't churn the thread's {@code updated_at}. Refuses to fall
     * back to the user's clone root: it may contain unrelated local edits,
     * which are not a safe planning base.
     */
    private static String resolveTrunkPlanningCwd(
            ThreadStore store,
            WorktreeService worktreeService,
            WorkspaceService workspaces,
            WatchedRepoStore watchedRepos,
            Thread thread)
    {
        String cloneRoot = resolveTrunkCwdForWorkspace(workspaces, watchedRepos, thread);
        Path repoRoot = Path.of(cloneRoot).toAbsolutePath().normalize();
        String pinnedSha = store.findPlanningSnapshot(thread.id())
                .filter(snapshot -> repoRoot.toString().equals(snapshot.repoRoot()))
                .map(ThreadStore.PlanningSnapshot::baseSha)
                .orElse(null);
        Optional<WorktreeService.PlanningSync> ready =
                worktreeService.syncPlanningWorktree(repoRoot, thread.id(), pinnedSha);
        ready.filter(sync -> !sync.baseSha().equals(pinnedSha))
                .ifPresent(sync -> store.setPlanningSnapshot(
                        thread.id(), new ThreadStore.PlanningSnapshot(
                                repoRoot.toString(), sync.baseSha())));
        return ready.map(sync -> sync.worktree().toString())
                .orElseThrow(() -> new IllegalStateException(
                        "planning snapshot unavailable for thread " + thread.id()));
    }

    private static String workspaceMemory(WorkspaceService workspaces, Thread thread)
    {
        String workspaceId = thread == null ? null : thread.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return "";
        }
        return workspaces.getMemory(workspaceId);
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
                thread -> workspaceMemoryProvider.get(), leaseService,
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
            Function<Thread, String> workspaceMemoryProvider,
            WorktreeLeaseService leaseService,
            Function<Thread, String> trunkCwdResolver,
            SkillMaterializer skillMaterializer,
            RoleRegistry roleRegistry,
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
        this.roleRegistry = roleRegistry;
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
            RoleRegistry roleRegistry,
            PonytailBundleService ponytailBundleService,
            WorkModelResolver workModelResolver,
            CredentialService credentialService,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            AgentContextDigest contextDigest)
    {
        this(store, taskStore, stageStore, parser, mapper, gate, executor,
                checkpointTrigger, thread -> workspaceMemoryProvider.get(), leaseService,
                trunkCwdResolver, skillMaterializer, roleRegistry,
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

    /** The live stage-agent for a thread + stage key (= stage id for a
     *  stage-scoped agent), if one exists. */
    public Optional<ThreadAgent> findStage(String threadId, String stageKey)
    {
        if (threadId == null || stageKey == null) {
            return Optional.empty();
        }
        ThreadAgent exact = sessions.get(new SessionKey(threadId, stageKey));
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(trunkSessions.get(threadId))
                .filter(agent -> stageKey.equals(agent.activeStageId()));
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
            ThreadAgent agent = sessions.get(new SessionKey(threadId, key));
            if (agent != null) {
                out.add(agent);
            }
        }
        return out;
    }

    /** The exact agent addressed by {@code agentKey}. Stage agents are filed
     *  under that key; a shared task Brain is matched by its active task/stage
     *  context in {@link #trunkSessions}. */
    public Optional<ThreadAgent> findByAgentKey(String threadId, String agentKey)
    {
        if (threadId == null || threadId.isBlank() || agentKey == null || agentKey.isBlank()) {
            return Optional.empty();
        }
        ThreadAgent exact = sessions.get(new SessionKey(threadId, agentKey));
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(trunkSessions.get(threadId))
                .filter(agent -> scopedBy(agent, agentKey));
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

    /** Apply a picker change to the next trunk turn without interrupting an
     *  in-flight subprocess. Switching CLI families drops the cached wrapper
     *  so the next turn rebuilds it with the matching provider adapter. */
    public void updateTrunkWorkModel(String threadId, WorkModel workModel)
    {
        ThreadAgent current = trunkSessions.get(threadId);
        if (current == null || workModel == null || workModel.kind() != WorkModelKind.CLI) {
            return;
        }
        boolean currentCodex = current instanceof CodexCliThreadAgent;
        boolean nextCodex = "codex".equals(workModel.agentOrProvider());
        if (currentCodex != nextCodex) {
            trunkSessions.remove(threadId, current);
            return;
        }
        current.updateWorkModel(workModel);
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
        String agentKey = stageKey(thread.id(), task, stageId);
        SessionKey key = new SessionKey(thread.id(), agentKey);
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
                built.setMcpAgentKey(agentKey);
                return built;
            });
            threadStageKeys.computeIfAbsent(thread.id(), id -> ConcurrentHashMap.newKeySet()).add(agentKey);
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
            SessionKey sessionKey = new SessionKey(threadId, key);
            sessions.remove(sessionKey);
            String leased = leasedWorktrees.remove(sessionKey);
            if (leased != null) {
                releaseLeaseQuietly(leased);
            }
        }
    }

    /** Every live agent filed under any of the supplied raw stage keys,
     *  across owning threads. A review stage can have both a development
     *  agent and a brain agent, so stage-close teardown must reach both. */
    public List<ThreadAgent> findStages(Collection<String> stageKeys)
    {
        if (stageKeys == null || stageKeys.isEmpty()) {
            return List.of();
        }
        return Stream.concat(
                        sessions.entrySet().stream()
                                .filter(entry -> stageKeys.contains(entry.getKey().agentKey()))
                                .map(entry -> entry.getValue()),
                        trunkSessions.values().stream()
                                .filter(agent -> stageKeys.stream().anyMatch(key -> scopedBy(agent, key))))
                .distinct()
                .toList();
    }

    /** Evict + release every stage-agent in {@code stageKeys}. A raw stage
     *  key belongs to one task but may have runtimes on its development and
     *  brain threads, so both are removed together. */
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
        // A task stage may own both the development agent and a read-only
        // brain agent on separate threads. Closing that stage reaps every
        // runtime filed under its raw key; pair-scoped lookups remain
        // available for live routing through findStage/findByAgentKey.
        for (SessionKey sessionKey : List.copyOf(sessions.keySet())) {
            if (!stageKey.equals(sessionKey.agentKey())) {
                continue;
            }
            sessions.remove(sessionKey);
            String leased = leasedWorktrees.remove(sessionKey);
            if (leased != null) {
                releaseLeaseQuietly(leased);
            }
            Set<String> keys = threadStageKeys.get(sessionKey.threadId());
            if (keys != null) {
                keys.remove(stageKey);
                if (keys.isEmpty()) {
                    threadStageKeys.remove(sessionKey.threadId(), keys);
                }
            }
        }
        for (var entry : List.copyOf(trunkSessions.entrySet())) {
            if (scopedBy(entry.getValue(), stageKey)) {
                trunkSessions.remove(entry.getKey(), entry.getValue());
            }
        }
        // Also clear a stale owner-index entry if its session disappeared
        // before this best-effort teardown ran.
        Set<String> ownerKeys = threadId == null ? null : threadStageKeys.get(threadId);
        if (ownerKeys != null) {
            ownerKeys.remove(stageKey);
            if (ownerKeys.isEmpty()) {
                threadStageKeys.remove(threadId, ownerKeys);
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

    private static boolean scopedBy(ThreadAgent agent, String key)
    {
        if (key == null) {
            return false;
        }
        return key.equals(agent.activeStageId())
                || key.equals(agent.activeTaskId())
                || ("trunk".equals(key)
                        && agent.activeTaskId() == null
                        && agent.activeStageId() == null);
    }

    /** In-memory identity of a stage agent. The MCP-facing agent key remains
     *  the raw stage/task key because its URL is already nested under the
     *  owning thread id. */
    private record SessionKey(String threadId, String agentKey) {}

    private void acquireLease(SessionKey sessionKey, Task active, String worktreePath)
    {
        Integer jvmPid = (int) ProcessHandle.current().pid();
        Optional<WorktreeLease> acquired = leaseService.tryAcquireOrReclaim(
                worktreePath, active.id(), ThreadKind.CLI_AGENT, jvmPid);
        if (acquired.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "worktree " + worktreePath + " is leased by another live session");
        }
        leasedWorktrees.put(sessionKey, worktreePath);
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
        // A brain thread points at its parent task, which belongs to the
        // development thread. Its model is therefore resolved from the brain
        // thread itself; asking the task/stage cascade to validate that pair
        // incorrectly reports "task is not on thread".
        WorkModel resolved = thread.kind() == ThreadKind.BRAIN_AGENT
                ? null
                : resolveWorkModelForStage(thread, boundTask, stageId);
        ThreadAgent agent = switch (thread.kind()) {
            case CLI_AGENT -> isCodex(thread, resolved)
                    ? new CodexCliThreadAgent(
                            thread, store, taskStore, codexParser, mapper, gate, executor, checkpointTrigger,
                            workspaceMemorySupplier(thread, stageAudience(thread, stageId)),
                            resolveTaskRoleSkill(boundTask),
                            boundTask, cliModelOverride(resolved), cliReasoningEffort(resolved))
                    : new ClaudeCodeCliThreadAgent(
                            thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                            workspaceMemorySupplier(thread, stageAudience(thread, stageId)), skillMaterializer,
                            resolveTaskRoleSkill(boundTask),
                            boundTask, cliModelOverride(resolved), cliReasoningEffort(resolved));
            case LOGIC_LOOP -> {
                String workingDir = boundTask != null
                        ? boundTask.workingDir()
                        : trunkCwdResolver.apply(thread);
                yield new LogicLoopThreadAgent(
                        thread, store, mapper, executor,
                        credentialService, resolved, workingDir,
                        roleWithKnowledge(
                                resolveTaskRoleSkill(boundTask), thread,
                                stageAudience(thread, stageId)),
                        toolRegistry,
                        ds4, ds4Instrumentation, gate);
            }
            case BRAIN_AGENT -> buildBrain(thread);
        };
        if (agent instanceof AbstractCliThreadAgent cli && boundTask != null) {
            Optional<Path> checkout = taskCheckout(boundTask);
            checkout.ifPresent(path -> {
                cli.setPreTurnHook(() -> {
                    codeGraph.ensureFreshWithin(path, "before-agent-turn", 15_000);
                    return null;
                });
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
        if (resolved.kind() != WorkModelKind.CLI) {
            return null;
        }
        // Empty is an intentional "let this CLI use its own default";
        // null means no cascade override was supplied and constructors may
        // fall back to the legacy model stored on the Thread row.
        return resolved.model() == null ? "" : resolved.model();
    }

    private static String cliReasoningEffort(WorkModel resolved)
    {
        return resolved.kind() == WorkModelKind.CLI ? resolved.reasoningEffort() : null;
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
                WorkModel resolved = resolveWorkModel(thread.id());
                // The resolver applies whatever base movement the background
                // fetcher brought down; an unmoved base reopens cheaply.
                String initialCwd = trunkCwdResolver.apply(thread);
                AbstractCliThreadAgent agent = isCodex(thread, resolved)
                        ? new CodexCliThreadAgent(
                                thread, store, taskStore, codexParser, mapper, gate, executor, checkpointTrigger,
                                workspaceMemorySupplier(thread, trunkAudience(thread)),
                                roleRegistry == null ? null : roleRegistry.trunkTemplate(),
                                initialCwd,
                                CodexCliThreadAgent.TrunkMode.ENABLED,
                                cliModelOverride(resolved),
                                cliReasoningEffort(resolved))
                        : new ClaudeCodeCliThreadAgent(
                                thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                                workspaceMemorySupplier(thread, trunkAudience(thread)), skillMaterializer,
                                roleRegistry == null ? null : roleRegistry.trunkTemplate(),
                                initialCwd,
                                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED,
                                cliModelOverride(resolved),
                                cliReasoningEffort(resolved));
                agent.setPreTurnHook(trunkPlanningPreTurnHook(thread));
                yield withManagedSkillBundle(agent);
            }
            case LOGIC_LOOP -> {
                WorkModel resolved = resolveWorkModel(thread.id());
                LogicLoopThreadAgent agent = new LogicLoopThreadAgent(
                        thread, store, mapper, executor,
                        credentialService, resolved, trunkCwdResolver.apply(thread),
                        roleWithKnowledge(
                                roleRegistry == null ? null : roleRegistry.trunkTemplate(),
                                thread, trunkAudience(thread)),
                        toolRegistry, ds4, ds4Instrumentation, gate);
                agent.setPreTurnHook(trunkPlanningPreTurnHook(thread));
                yield withManagedSkillBundle(agent);
            }
            // Brain turns carry no task id in the turn row, but the thread
            // kind still builds the task-brain runtime, not a trunk planner.
            case BRAIN_AGENT -> buildBrain(thread);
        };
    }

    /**
     * Pre-turn hook for trunk sessions: sync the planning worktree via the
     * cwd resolver, and when the base actually moved return a note for this
     * turn's model input so the agent re-verifies earlier assumptions
     * instead of planning against memories of the old checkout.
     */
    private Supplier<String> trunkPlanningPreTurnHook(Thread thread)
    {
        return () -> {
            String before = store.findPlanningSnapshot(thread.id())
                    .map(ThreadStore.PlanningSnapshot::baseSha)
                    .orElse(null);
            String synced = trunkCwdResolver.apply(thread);
            log.debug("trunk {} planning snapshot ready at {}", thread.id(), synced);
            String after = store.findPlanningSnapshot(thread.id())
                    .map(ThreadStore.PlanningSnapshot::baseSha)
                    .orElse(null);
            if (before == null || after == null || after.equals(before)) {
                return null;
            }
            return "[Planning base updated " + shortSha(before) + " -> " + shortSha(after)
                    + ": the base branch moved since your last turn (e.g. a PR merged). "
                    + "File contents and line numbers read in earlier turns may be stale — "
                    + "re-verify against the refreshed checkout before relying on them.]";
        };
    }

    private static String shortSha(String sha)
    {
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    /**
     * Build the brain agent for a brain thread, following its resolved work
     * model: the selected CLI subprocess on a CLI install, or the in-JVM
     * {@link LogicLoopThreadAgent} on an API install. Either way its system
     * prompt is the read-only brain template + the task's iteration digest,
     * and its working directory is the parent task's so file/git read tools
     * resolve against the right clone.
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
            return switch (resolved.agentOrProvider()) {
                case "codex" -> withManagedSkillBundle(new CodexCliThreadAgent(
                        thread, store, taskStore, codexParser, mapper, gate, executor, checkpointTrigger,
                        workspaceMemorySupplier(thread, "plan"), brainSystemPrompt(thread), workingDir,
                        CodexCliThreadAgent.TrunkMode.ENABLED,
                        cliModelOverride(resolved), cliReasoningEffort(resolved)));
                case "claude-code" -> withManagedSkillBundle(new ClaudeCodeCliThreadAgent(
                        thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                        workspaceMemorySupplier(thread, "plan"), skillMaterializer,
                        brainSystemPrompt(thread), workingDir,
                        ClaudeCodeCliThreadAgent.TrunkMode.ENABLED,
                        cliModelOverride(resolved), cliReasoningEffort(resolved)));
                default -> throw new IllegalArgumentException(
                        "unsupported CLI brain agent: " + resolved.agentOrProvider());
            };
        }
        return withManagedSkillBundle(new LogicLoopThreadAgent(
                thread, store, mapper, executor,
                credentialService, resolved, workingDir,
                roleWithKnowledge(brainSystemPrompt(thread), thread, "plan"),
                toolRegistry,
                ds4, ds4Instrumentation, gate));
    }

    private Supplier<String> workspaceMemorySupplier(Thread thread, String audience)
    {
        if (sessionKnowledge != null) {
            // The thread title is the retrieval hint: a scheduler thread pulls
            // scheduler knowledge into context, not unrelated conventions.
            return () -> sessionKnowledge.render(
                    thread.workspaceId(), audience, thread.title());
        }
        return () -> workspaceMemoryProvider.apply(thread);
    }

    private String roleWithKnowledge(String role, Thread thread, String audience)
    {
        if (sessionKnowledge == null) {
            return role;
        }
        String context = sessionKnowledge.render(
                thread.workspaceId(), audience, thread.title());
        if (context.isBlank()) {
            return role;
        }
        if (role == null || role.isBlank()) {
            return "# Workspace memory and knowledge\n\n" + context;
        }
        return role.strip() + "\n\n# Workspace memory and knowledge\n\n" + context;
    }

    private String trunkAudience(Thread thread)
    {
        return thread.flow() == ThreadFlow.REVIEW
                ? "review"
                : "plan";
    }

    private String stageAudience(Thread thread, String stageId)
    {
        if (thread.flow() == ThreadFlow.REVIEW) {
            return "review";
        }
        if (stageStore == null || stageId == null || stageId.isBlank()) {
            return "dev";
        }
        try {
            StageType type = stageStore.findStageById(UUID.fromString(stageId))
                    .map(stage -> stage.type())
                    .orElse(null);
            if (type == StageType.CI_FIXING_STAGE) {
                return "ci-fix";
            }
            if (type == StageType.PLAN_STAGE) {
                return "plan";
            }
            if (type == StageType.REVIEW_STAGE || type == StageType.REVIEW_ROUND_STAGE) {
                return "review";
            }
        }
        catch (IllegalArgumentException ignored) {
            // A legacy non-UUID stage key is ordinary development work.
        }
        return "dev";
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
            return roleRegistry == null ? LogicLoopThreadAgent.BRAIN_SYSTEM_PROMPT : roleRegistry.brainTemplate();
        }
        String digest = contextDigest.build(
                thread.parentTaskId(), AgentContextDigest.DEFAULT_CAP_TOKENS);
        String role = roleRegistry == null ? LogicLoopThreadAgent.BRAIN_SYSTEM_PROMPT : roleRegistry.brainTemplate();
        return role + "\n\n" + digest;
    }

    /** Whether a CLI-agent thread should run the {@code codex} binary
     *  rather than {@code claude}. Keyed on the provider stored at thread
     *  creation ({@code "codex"} vs {@code "claude-code"}), so it doesn't
     *  need the work-model resolver. */
    private static boolean isCodex(Thread thread)
    {
        return "codex".equals(thread.provider());
    }

    private static boolean isCodex(Thread thread, WorkModel resolved)
    {
        if (resolved.kind() == WorkModelKind.CLI) {
            return "codex".equals(resolved.agentOrProvider());
        }
        return isCodex(thread);
    }

    private WorkModel resolveWorkModel(String threadId)
    {
        if (workModelResolver != null) {
            WorkModelResolver.Resolved resolved = workModelResolver.resolveForThread(threadId);
            if (resolved != null) {
                return resolved.choice();
            }
        }
        // Fallback for test paths where the resolver isn't wired.
        return new WorkModel(WorkModelKind.API, "anthropic", null, null);
    }

    /** Render the bound task's versioned ByteQuay role, or null for trunk. */
    private String resolveTaskRoleSkill(Task boundTask)
    {
        if (boundTask == null) {
            return null;
        }
        return roleRegistry == null ? boundTask.roleSkill() : roleRegistry.resolveForTask(boundTask);
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
