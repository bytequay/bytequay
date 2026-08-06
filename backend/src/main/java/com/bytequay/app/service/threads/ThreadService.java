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

import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.developmentflow.task.creation.V2TaskCreationService;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestRef;
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
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadGroupStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.UncheckedGitException;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workspaces.WorkspaceDataPurger;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final PullRequestService pullRequests;
    private final ThreadGroupStore groupStore;
    private final ThreadTurnStore turnStore;
    private final ThreadTurnEventStore turnEventStore;
    private final ThreadRegistry registry;
    private final ThreadTurnScheduler scheduler;
    private final GitRunner git;
    private final WorktreeService worktreeService;
    private final IdGenerator idGenerator;
    private final WorkspaceDataPurger dataPurger;
    /** Set by Spring; POJO unit tests construct this service directly and do
     * not persist AgentReview-owned threads. */
    private InvestigationReviewService investigationReviews;
    /** Set by Spring so direct POJO tests that use the legacy create overload
     *  do not need to construct persistence infrastructure they never touch. */
    private ThreadEngineOverrides threadEngines;
    /** Creation-time resolver pair. Spring supplies it; direct POJO tests use
     *  the legacy no-snapshot path unless they exercise this integration. */
    private WorkModelResolver workModelResolver;
    private WorkModelService workModels;
    /** Permanent production cutover boundary. */
    private V2TaskCreationService v2TaskCreation;
    /** Typed Trunk runtime, supplied only when the V2 dispatcher graph exists. */
    private V2ThreadControlService v2ThreadControls;
    /** Read-only adapter for V2-owned branch/worktree/PR facts. */
    private V2DevelopmentFlowProjection v2TaskProjection;
    /** Read-only replacement for stale legacy Thread lifecycle fields. */
    private V2TrunkRuntimeProjection v2TrunkRuntime;

    public ThreadService(
            ThreadStore store,
            TaskStore taskStore,
            ThreadGroupStore groupStore,
            ThreadTurnStore turnStore,
            ThreadTurnEventStore turnEventStore,
            ThreadRegistry registry,
            McpPermissionGate gate,
            ThreadTurnScheduler scheduler,
            WorktreeLeaseService leases,
            GitRunner git,
            WorktreeService worktreeService,
            RoleRegistry roleRegistry,
            IdGenerator idGenerator,
            @Lazy PullRequestService pullRequests,
            WorkspaceDataPurger dataPurger,
            CheckpointSummariser titleSummariser)
    {
        this.store = requireNonNull(store, "store is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.groupStore = requireNonNull(groupStore, "groupStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
        this.registry = requireNonNull(registry, "registry is null");
        requireNonNull(gate, "gate is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        requireNonNull(leases, "leases is null");
        this.git = requireNonNull(git, "git is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        requireNonNull(roleRegistry, "roleRegistry is null");
        this.idGenerator = requireNonNull(idGenerator, "idGenerator is null");
        this.dataPurger = requireNonNull(dataPurger, "dataPurger is null");
        requireNonNull(titleSummariser, "titleSummariser is null");
    }

    @Autowired
    void setInvestigationReviews(@Lazy InvestigationReviewService investigationReviews)
    {
        this.investigationReviews = requireNonNull(investigationReviews, "investigationReviews is null");
    }

    @Autowired
    void setThreadEngines(ThreadEngineOverrides threadEngines)
    {
        this.threadEngines = requireNonNull(threadEngines, "threadEngines is null");
    }

    @Autowired
    void setWorkModelResolution(WorkModelResolver workModelResolver, WorkModelService workModels)
    {
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
        this.workModels = requireNonNull(workModels, "workModels is null");
    }

    @Autowired
    void setV2TaskCreation(V2TaskCreationService v2TaskCreation)
    {
        this.v2TaskCreation = requireNonNull(
                v2TaskCreation, "v2TaskCreation is null");
    }

    @Autowired(required = false)
    void setV2ThreadControls(V2ThreadControlService v2ThreadControls)
    {
        this.v2ThreadControls = requireNonNull(
                v2ThreadControls, "v2ThreadControls is null");
    }

    @Autowired(required = false)
    void setV2TaskProjection(V2DevelopmentFlowProjection v2TaskProjection)
    {
        this.v2TaskProjection = requireNonNull(
                v2TaskProjection, "v2TaskProjection is null");
    }

    @Autowired(required = false)
    void setV2TrunkRuntime(V2TrunkRuntimeProjection v2TrunkRuntime)
    {
        this.v2TrunkRuntime = requireNonNull(
                v2TrunkRuntime, "v2TrunkRuntime is null");
    }

    public List<Thread> listByStatus(ThreadStatus status, int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        if (v2TrunkRuntime == null) {
            return store.listTasksByStatus(status, limit);
        }
        int storedLimit = expandedLimit(limit, v2TrunkRuntime.count(null));
        List<Thread> rows = new ArrayList<>(
                store.listTasksByStatus(status, storedLimit));
        List<String> v2Ids = v2TrunkRuntime.listIds(status, null, limit);
        rows.addAll(store.listTasksByIds(v2Ids));
        return limitThreads(v2TrunkRuntime.projectAll(rows).stream()
                .filter(thread -> thread.status() == status)
                .toList(), limit);
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
        if (v2TrunkRuntime == null) {
            return store.listTasksByWorkspaceAndStatus(workspaceId, status, limit);
        }
        int storedLimit = expandedLimit(
                limit, v2TrunkRuntime.count(workspaceId));
        List<Thread> rows = new ArrayList<>(
                store.listTasksByWorkspaceAndStatus(
                        workspaceId, status, storedLimit));
        List<String> v2Ids = v2TrunkRuntime.listIds(
                status, workspaceId, limit);
        rows.addAll(store.listTasksByIds(v2Ids));
        return limitThreads(v2TrunkRuntime.projectAll(rows).stream()
                .filter(thread -> thread.status() == status)
                .toList(), limit);
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
        if (v2TrunkRuntime != null) {
            rows = v2TrunkRuntime.projectAll(rows);
        }
        return limitThreads(rows, limit);
    }

    private static int expandedLimit(int limit, int additional)
    {
        return additional > Integer.MAX_VALUE - limit
                ? Integer.MAX_VALUE : limit + additional;
    }

    private static List<Thread> limitThreads(List<Thread> rows, int limit)
    {
        return rows.stream()
                .collect(Collectors.toMap(
                        Thread::id, thread -> thread, (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(Thread::updatedAt)
                        .thenComparing(Thread::id).reversed())
                .limit(limit)
                .toList();
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
        List<ThreadGroupMembership> members = groupStore.listMembers(groupId);
        // Re-adding an existing member shouldn't trip the cap: the
        // store's addMember is idempotent so the count won't change.
        boolean alreadyMember = members.stream().anyMatch(m -> m.threadId().equals(threadId));
        if (!alreadyMember && members.size() >= GROUP_MAX_MEMBERS) {
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
        List<ThreadGroupMembership> members = groupStore.listMembers(groupId);
        if (members.stream().noneMatch(m -> m.threadId().equals(threadId))) {
            return;
        }
        if (members.size() <= 1) {
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
        requireV2Trunk(current);

        String nextTitle = current.title();
        if (patch.title() != null && !patch.title().isBlank()) {
            nextTitle = uniqueTitleInWorkspace(current.workspaceId(), patch.title().trim(), threadId);
        }

        Thread next = new Thread(
                current.id(), current.kind(), current.provider(), current.agentSessionId(),
                nextTitle, current.status(),
                current.model(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.createdAt(), Instant.now(),
                current.endedAt(), current.errorMessage(),
                current.flow(),
                current.workspaceId(),
                current.workModel(), current.parentReviewPassId(),
                current.parallelSlots(), current.parentTaskId(),
                current.prRef(), current.description());
        store.saveThread(next);
        return projectTrunkRuntime(store.findThreadById(threadId).orElse(next));
    }

    /**
     * Set (or clear) the thread's override on the work-model cascade.
     * Passing {@code null} clears the override so the resolver falls
     * back to the workspace pick. Returns the updated row so the caller
     * can refresh without a follow-up fetch.
     */
    @Transactional
    public Thread setWorkModel(String threadId, WorkModel workModel)
    {
        requireNonNull(threadId, "threadId is null");
        Thread current = requireTask(threadId);
        requireV2Trunk(current);
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "V2 Trunk engines are frozen at creation");
    }

    /** Make a persisted thread-scope picker change visible to its next trunk
     *  subprocess turn. Running turns keep the command they already spawned. */
    public void updateTrunkWorkModel(String threadId, WorkModel resolved)
    {
        Thread current = requireTask(threadId);
        requireV2Trunk(current);
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "V2 Trunk engines are frozen at creation");
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
        validateCreateRequest(request);
        Map<String, WorkModel> snapshot = workModelResolver == null || workModels == null
                ? Map.of()
                : freezeEngines(request.workspaceId(), Map.of());
        return createWithSnapshot(request, snapshot);
    }

    /**
     * Create from the dialog's sparse picker overrides. All four effective
     * engines are resolved before persistence, then share the transaction with
     * the thread and group memberships.
     */
    @Transactional
    public Thread createWithEngineOverrides(
            NewTaskRequest request,
            Map<String, String> engineOverrides)
    {
        validateCreateRequest(request);
        requireNonNull(workModelResolver, "workModelResolver is null");
        requireNonNull(workModels, "workModels is null");
        return createWithSnapshot(
                request, freezeEngines(request.workspaceId(), engineOverrides));
    }

    private Thread createWithSnapshot(
            NewTaskRequest request,
            Map<String, WorkModel> engineSnapshot)
    {
        if (!routesNewTaskToV2(request.workspaceId())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Trunk creation is not configured");
        }
        if (!engineSnapshot.isEmpty()) {
            request = request.withEngine(requireNonNull(
                    engineSnapshot.get(SessionAudience.PLAN), "plan engine is null"));
        }
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
        // Human-readable id of the form t<ymd>-<seq>-<rand2> — embeds
        // the creation day and a per-day counter so threads in logs
        // and on disk identify themselves. See service/ids/IdGenerator.
        String threadId = idGenerator.newThreadId(now);
        String title = uniqueTitleInWorkspace(
                request.workspaceId().trim(), deriveTitle(request.title(), request.initialPrompt()), null);
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
                request.workspaceId().trim(),
                request.workModel(),
                /* parentReviewPassId */ null,
                /* parallelSlots */ 1,
                /* parentTaskId */ null,
                /* prRef */ null,
                request.description());
        store.saveThread(thread);
        for (String groupId : initialGroupIds) {
            groupStore.addMember(thread.id(), groupId);
        }
        if (engineSnapshot != null && !engineSnapshot.isEmpty()) {
            requireNonNull(threadEngines, "threadEngines is null")
                    .replace(thread.id(), engineSnapshot);
        }
        v2TaskCreation.prepareNewTrunk(thread.id(), thread.workspaceId());
        // initialPrompt — if present — feeds the title derivation
        // above but is NOT enqueued as a trunk turn. Treat it as
        // setup context the user prepared in the create dialog; the
        // trunk page stages it in the composer so the user reviews
        // before pressing Send. Nothing reaches the planning agent
        // until they do.
        return projectTrunkRuntime(
                store.findThreadById(thread.id()).orElse(thread));
    }

    private Map<String, WorkModel> freezeEngines(
            String workspaceId,
            Map<String, String> requested)
    {
        Map<String, String> overrides = requested == null ? Map.of() : requested;
        for (String audience : overrides.keySet()) {
            if (audience == null || !SessionAudience.ALL.contains(audience)) {
                throw new IllegalArgumentException("unknown session audience: " + audience);
            }
        }

        Map<String, WorkModel> frozen = new LinkedHashMap<>();
        for (String audience : SessionAudience.ALL) {
            WorkModel choice;
            if (overrides.containsKey(audience)) {
                choice = workModels.freezeChoice(overrides.get(audience))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "invalid engine choice for " + audience));
            }
            else {
                choice = workModels.freeze(workModelResolver
                        .resolveForWorkspace(workspaceId, audience).choice());
            }
            frozen.put(audience, choice);
        }
        return Map.copyOf(frozen);
    }

    private static void validateCreateRequest(NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        if (request.workspaceId() == null || request.workspaceId().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "workspaceId is required — every thread belongs to a workspace");
        }
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
    public Task materialiseTask(String threadId, NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        Thread thread = requireTask(threadId);
        requireV2Trunk(thread);
        if (v2TaskCreation == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(503),
                    "V2 Task creation is not configured");
        }
        return v2TaskCreation.create(thread, request);
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
        String summary = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(6, words.length)));
        if (summary.length() > 60) {
            summary = summary.substring(0, 57) + "…";
        }
        return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
    }

    /** Disambiguate {@code candidate} against every other thread title in
     *  {@code workspaceId} by appending " (2)", " (3)", ... until it's
     *  unique. {@code excludeThreadId} is skipped so a no-op rename (or
     *  the thread being created) doesn't collide with itself. */
    private String uniqueTitleInWorkspace(String workspaceId, String candidate, String excludeThreadId)
    {
        Set<String> existing = new HashSet<>();
        for (Thread t : store.listThreadsByWorkspace(workspaceId)) {
            if (excludeThreadId == null || !t.id().equals(excludeThreadId)) {
                existing.add(t.title());
            }
        }
        if (!existing.contains(candidate)) {
            return candidate;
        }
        int suffix = 2;
        String next;
        do {
            next = candidate + " (" + suffix + ")";
            suffix++;
        } while (existing.contains(next));
        return next;
    }

    public Optional<Thread> find(String threadId)
    {
        return store.findThreadById(threadId).map(this::projectTrunkRuntime);
    }

    private Thread projectTrunkRuntime(Thread thread)
    {
        return v2TrunkRuntime == null ? thread : v2TrunkRuntime.project(thread);
    }

    public List<ThreadFile> files(String threadId)
    {
        return store.listFiles(threadId);
    }

    public List<ThreadMessage> history(String threadId)
    {
        if (isV2Trunk(threadId)) {
            return Stream.concat(
                            store.listMessages(threadId).stream()
                                    .filter(ThreadService::isRetainedTrunkMessage),
                            requireV2ThreadControls().history(threadId).stream())
                    .toList();
        }
        return store.listMessages(threadId);
    }

    /** A provider-native trunk conversation cannot move to another agent
     *  after its first user message; model changes within that agent remain
     *  valid. */
    public boolean isWorkModelAgentLocked(String threadId)
    {
        requireTask(threadId);
        if (isV2Trunk(threadId)) {
            return !history(threadId).isEmpty();
        }
        return store.countUserMessages(threadId) > 0;
    }

    /** Recent scheduler turns for one thread, newest first. */
    public List<ThreadTurn> turns(String threadId)
    {
        Thread thread = requireTask(threadId);
        if (isV2Trunk(thread.id())) {
            return Stream.concat(
                            turnStore.listTurnsByTaskId(
                                    thread.id(), TURN_HISTORY_LIMIT).stream(),
                            requireV2ThreadControls().turns(
                                    thread.id(), TURN_HISTORY_LIMIT).stream())
                    .sorted(Comparator.comparing(ThreadTurn::createdAt)
                            .thenComparing(ThreadTurn::id).reversed())
                    .limit(TURN_HISTORY_LIMIT)
                    .toList();
        }
        return turnStore.listTurnsByTaskId(thread.id(), TURN_HISTORY_LIMIT);
    }

    /** Recent scheduler events for one thread, newest first. */
    public List<ThreadTurnEvent> turnEvents(String threadId)
    {
        Thread thread = requireTask(threadId);
        if (isV2Trunk(thread.id())) {
            return Stream.concat(
                            turnEventStore.listEventsByTaskId(
                                    thread.id(), TURN_EVENT_HISTORY_LIMIT).stream(),
                            requireV2ThreadControls().turnEvents(thread.id()).stream())
                    .sorted(Comparator.comparing(ThreadTurnEvent::createdAt)
                            .thenComparing(ThreadTurnEvent::id).reversed())
                    .limit(TURN_EVENT_HISTORY_LIMIT)
                    .toList();
        }
        return turnEventStore.listEventsByTaskId(thread.id(), TURN_EVENT_HISTORY_LIMIT);
    }

    /** Queued/running turns across all threads, oldest first. */
    public List<ThreadTurn> activeTurns(int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        int capped = Math.min(limit, ACTIVE_TURN_LIMIT);
        List<ThreadTurn> legacy = turnStore.listTurnsByStatuses(
                List.of(ThreadTurnStatus.RUNNING, ThreadTurnStatus.QUEUED),
                capped);
        List<ThreadTurn> typed = v2ThreadControls == null
                ? List.of() : v2ThreadControls.activeTurns(capped);
        return Stream.concat(legacy.stream(), typed.stream())
                .sorted(Comparator.comparing(ThreadTurn::createdAt)
                        .thenComparing(ThreadTurn::id))
                .limit(capped)
                .toList();
    }

    /** Send a task-scoped follow-up. The task id is mandatory and its
     *  ownership is checked against the expected trunk before enqueue. */
    public String send(String threadId, String taskId, String input)
    {
        requireTask(threadId);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required for a task turn");
        }
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (!threadId.equals(task.threadId())) {
            throw new IllegalArgumentException(
                    "task " + taskId + " does not belong to thread " + threadId);
        }
        if (!taskStore.isV2Task(task.id())) {
            throw legacyRuntimeRetired("Task", task.id());
        }
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "V2 Task follow-ups must target its current Stage");
    }

    /** Trunk-scope counterpart of {@link #send} — drives the trunk
     *  planning agent for cross-task talk. The row lands with
     *  {@code task_id = null} so it filters into the trunk slice
     *  rather than any Task's segment. */
    public String sendTrunk(String threadId, String input)
    {
        Thread thread = requireTask(threadId);
        requireV2Trunk(thread);
        return requireV2ThreadControls().send(thread, input, TurnInitiator.user());
    }

    /** Background counterpart to {@link #sendTrunk}; the durable initiator
     *  keeps approval policy in unattended mode. */
    public String sendTrunkUnattended(String threadId, String input, String source)
    {
        Thread thread = requireTask(threadId);
        TurnInitiator initiator = TurnInitiator.unattended(source);
        requireV2Trunk(thread);
        return requireV2ThreadControls().send(thread, input, initiator);
    }

    public void interruptTrunk(String threadId)
    {
        interruptTrunk(threadId, null);
    }

    public void interruptTrunk(String threadId, String turnId)
    {
        requireNonNull(threadId, "threadId is null");
        Thread thread = requireTask(threadId);
        requireTrunkThread(thread);
        requireV2Trunk(thread);
        requireV2ThreadControls().interrupt(thread.id(), turnId);
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
     *     shouldn't have to wait for it).
     *
     * <p>It does NOT mark the thread's parked notifications read:
     * jump-in transfers the lease but doesn't resolve the parked work,
     * so those rows stay visible until the proposal is approved/discarded
     * or the stuck task is resolved.
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
        requireV2Trunk(thread);
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "V2 jump-in requires typed Task and Stage quiescence");
    }

    public void resume(String threadId)
    {
        Thread thread = store.findThreadById(threadId)
                .orElseThrow(() -> new NoSuchElementException("no thread: " + threadId));
        requireV2Trunk(thread);
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "V2 Trunk turns resume through their durable dispatcher owner");
    }

    public void stop(String threadId)
    {
        Thread thread = requireTask(threadId);
        requireV2Trunk(thread);
        requireV2ThreadControls().interrupt(thread.id());
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
        requireV2Trunk(existing.orElseThrow());
        boolean v2 = true;
        // Refuse while any task is still in flight — running / queued
        // tasks hold worktrees and live agent processes, and deleting
        // out from under them would strand both. A zero-task
        // brainstorm thread is fine to drop. Once every task is
        // COMPLETED the user can clean up the thread.
        List<Task> allTasks = taskStore.listTasksByThread(threadId);
        long unfinished = allTasks.stream()
                .filter(task -> !v2 || !taskStore.isV2Task(task.id()))
                .filter(t -> t.status() != TaskStatus.COMPLETED && t.status() != TaskStatus.ARCHIVED
                        && t.status() != TaskStatus.REMOTE_CLOSED)
                .count();
        if (unfinished > 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "Thread " + threadId + " has " + unfinished
                            + " task" + (unfinished == 1 ? "" : "s")
                            + " that haven't completed — finish or stop them"
                            + " before deleting the thread.");
        }
        V2ThreadControlService.DeletionPermit permit = v2
                ? prepareV2Deletion(threadId) : null;
        teardownAndDelete(threadId, allTasks, permit);
    }

    /**
     * Force-delete a thread and everything under it, <em>without</em> the
     * shipped-task guard {@link #delete} enforces. This is the teardown a
     * workspace cascade-delete runs per thread: the whole workspace is
     * going away, so in-flight tasks are stopped and their worktrees reaped
     * rather than refused. Stops every live agent (stage + trunk), cancels
     * queued turns, evicts the sessions, reaps each task's worktree, then
     * drops the row (the DB cascades tasks / stages / messages / backlog).
     *
     * <p>Idempotent on a missing id. Not exposed over HTTP — only the
     * workspace teardown calls it, so a single thread always goes through
     * the guarded {@link #delete}.
     */
    public void purge(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        if (store.findThreadById(threadId).isEmpty()) {
            return;
        }
        Thread thread = store.findThreadById(threadId).orElseThrow();
        requireV2Trunk(thread);
        boolean v2 = true;
        V2ThreadControlService.DeletionPermit permit = v2
                ? prepareV2Deletion(threadId) : null;
        teardownAndDelete(
                threadId, taskStore.listTasksByThread(threadId), permit);
    }

    /**
     * Shared teardown for {@link #delete} / {@link #purge}: stop + evict
     * every agent session (so no subprocess outlives the row), drain queued
     * turns, reap each task's worktree, then delete the thread row.
     */
    private void teardownAndDelete(
            String threadId,
            List<Task> allTasks,
            V2ThreadControlService.DeletionPermit permit)
    {
        // Stop the live agents + drop any queued turns so we don't leave a
        // subprocess running against a deleted row. A thread may have
        // several Task agents plus its trunk/planning agent.
        for (ThreadAgent agent : registry.findAll(threadId)) {
            stopQuietly(threadId, agent);
        }
        registry.findTrunk(threadId).ifPresent(agent -> stopQuietly(threadId, agent));
        scheduler.cancelQueuedTurns(threadId);
        registry.evict(threadId);
        registry.evictTrunk(threadId);
        // Best-effort worktree cleanup. Errors are logged inside the
        // service; we don't fail the delete if a worktree is already gone
        // or git can't remove it cleanly — the thread row going away is the
        // authoritative signal. Remove every task's worktree: deleting the
        // thread retires all its work, not just whichever task was active.
        for (Task task : allTasks) {
            if (task.worktreePath() != null && !task.worktreePath().isBlank()
                    && task.workingDir() != null && !task.workingDir().isBlank()) {
                worktreeService.remove(Path.of(task.workingDir()),
                        task.worktreePath(), task.branchName());
            }
        }
        // The planning checkout is thread-owned, so it outlives individual
        // tasks but must not outlive a permanently deleted thread. Task
        // working dirs are also probed as roots in case the snapshot row is
        // absent (never planned, or pre-dates it) — the cleanup is idempotent.
        Stream.concat(
                        store.findPlanningSnapshot(threadId).stream()
                                .map(ThreadStore.PlanningSnapshot::repoRoot),
                        allTasks.stream().map(Task::workingDir))
                .filter(root -> root != null && !root.isBlank())
                .distinct()
                .forEach(root -> worktreeService.removePlanningWorktree(
                        Path.of(root), threadId));
        Runnable deleteRows = () -> {
            if (investigationReviews != null) {
                investigationReviews.purgeByOwnerThread(threadId);
            }
            dataPurger.purgeThreadScoped(
                    threadId, allTasks.stream().map(Task::id).toList());
            store.deleteThread(threadId);
        };
        if (permit == null) {
            deleteRows.run();
        }
        else {
            requireV2ThreadControls().delete(permit, deleteRows);
        }
    }

    private V2ThreadControlService.DeletionPermit prepareV2Deletion(
            String threadId)
    {
        try {
            return requireV2ThreadControls().prepareDeletion(threadId);
        }
        catch (RuntimeException failure) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), failure.getMessage(), failure);
        }
    }

    private boolean isV2Trunk(String threadId)
    {
        return store.findTurnVersion(threadId).filter("V2"::equals).isPresent();
    }

    private void requireV2Trunk(Thread thread)
    {
        if (!isV2Trunk(thread.id())) {
            throw legacyRuntimeRetired("Trunk", thread.id());
        }
    }

    private static ResponseStatusException legacyRuntimeRetired(
            String ownerKind, String ownerId)
    {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "Historical LEGACY " + ownerKind + " " + ownerId
                        + " is read-only; use a typed V2 owner");
    }

    private static boolean isRetainedTrunkMessage(ThreadMessage message)
    {
        boolean trunkScope = message.scope() == ThreadScope.TRUNK
                || message.scope() == null;
        return trunkScope
                && message.taskId() == null
                && message.stageId() == null;
    }

    private boolean routesNewTaskToV2(String workspaceId)
    {
        return v2TaskCreation != null && v2TaskCreation.routes(workspaceId);
    }

    private V2ThreadControlService requireV2ThreadControls()
    {
        if (v2ThreadControls == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(503),
                    "V2 Trunk runtime is not configured");
        }
        return v2ThreadControls;
    }

    private void stopQuietly(String threadId, ThreadAgent agent)
    {
        try {
            agent.stop();
        }
        catch (RuntimeException e) {
            log.warn("agent stop on delete threw for {}: {}", threadId, e.getMessage());
        }
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
        boolean v2 = isV2Trunk(threadId);
        long unfinished = taskStore.listTasksByThread(threadId).stream()
                .filter(task -> !v2 || !taskStore.isV2Task(task.id()))
                .filter(t -> t.status() != TaskStatus.COMPLETED && t.status() != TaskStatus.ARCHIVED
                        && t.status() != TaskStatus.REMOTE_CLOSED)
                .count();
        if (unfinished > 0) {
            return Optional.of(unfinished + " task" + (unfinished == 1 ? " is" : "s are")
                    + " still in flight — finish or stop them before deleting.");
        }
        if (v2) {
            return requireV2ThreadControls().deletionBlocker(threadId);
        }
        return Optional.empty();
    }

    // ── Working-tree + commit views for the Tasks UI tabs ────────────
    // Each call resolves the thread to its agent cwd, then delegates
    // to GitRunner. We don't cache — these views are opened on demand
    // and the underlying tree mutates as the AI session writes files.
    // Wrap the checked IO/Interrupted exceptions so callers stay clean.

    /** A git-backed call that may throw the checked exceptions
     *  {@link GitRunner} surfaces. */
    @FunctionalInterface
    private interface GitCall<T>
    {
        T run()
                throws IOException, InterruptedException;
    }

    /** Run a git-backed call, mapping its checked IO / Interrupted
     *  failures to a uniform unchecked error (restoring the interrupt
     *  flag) so the read surfaces stay one-liners. {@code action} reads
     *  into the message, e.g. {@code "list commits for t-1"}. */
    private static <T> T runGit(String action, GitCall<T> call)
    {
        try {
            return call.run();
        }
        catch (IOException e) {
            throw new UncheckedGitException("Failed to " + action, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while trying to " + action, e);
        }
    }

    /** Files the AI session has modified but not yet committed —
     *  feeds the "Files" tab. Mirrors {@code git status --porcelain}. */
    public List<GitRunner.WorkingTreeFile> listWorkingChanges(String threadId, String taskId)
    {
        Thread thread = requireTask(threadId);
        // Hide the app's own per-worktree hook dir — it's ByteQuay
        // infrastructure (the BQ-Task commit-trailer hook), not the
        // user's work, so it has no business in the Changed-files list.
        return runGit("list working-tree changes for " + threadId,
                () -> git.workingTreeFiles(agentCwd(thread, taskId)).stream()
                        .filter(f -> !isHookDirPath(f.path()))
                        .toList());
    }

    /** True for the app's hook dir, reported by porcelain as the bare
     *  directory ({@code .bytequay-hooks/}) or, if ever expanded, any
     *  path beneath it. */
    private static boolean isHookDirPath(String path)
    {
        return path.equals(WorktreeService.HOOK_DIR_REL)
                || path.startsWith(WorktreeService.HOOK_DIR_REL + "/");
    }

    /** Unified diff for one uncommitted file. */
    public String getWorkingDiff(String threadId, String taskId, String path)
    {
        requireNonNull(path, "path is null");
        Thread thread = requireTask(threadId);
        Optional<Path> cwd = existingAgentCwd(thread, taskId);
        if (cwd.isEmpty()) {
            return "";
        }
        return runGit("diff " + path + " for " + threadId,
                () -> git.workingTreeFileDiff(cwd.get(), path, DIFF_MAX_BYTES));
    }

    /** The commits this task ADDED on top of its base branch
     *  ({@code git log <base>..HEAD}). Scoped to the task's own work, not
     *  the base history the worktree was cut from — a time-based filter
     *  over-includes commits that landed on the base branch during the
     *  task's lifetime (they're recent but aren't the task's). */
    public List<GitRunner.CommitEntry> listTaskCommits(String threadId, String taskId)
    {
        Thread thread = requireTask(threadId);
        Task task = resolveSurfaceTask(threadId, taskId).orElse(null);
        // A merged task's worktree gets reaped (deleted) by the lifecycle
        // driver, so the dir can be gone while the task row lives on. Fall
        // back to the PR's commits on GitHub so a merged task stays viewable
        // instead of showing an empty Commits tab.
        Optional<Path> cwd = existingAgentCwd(thread, taskId);
        if (cwd.isEmpty()) {
            return taskPrLocator(task)
                    .map(pr -> pullRequests.getPullRequestCommits(pr.repoFullName(), pr.number())
                            .stream().map(ThreadService::toCommitEntry).toList())
                    .orElseGet(List::of);
        }
        return runGit("list commits for " + threadId, () -> {
            String base = taskDiffBase(cwd.get(), task);
            return base == null ? List.<GitRunner.CommitEntry>of()
                    : git.listCommitsAhead(cwd.get(), base, COMMITS_LIMIT);
        });
    }

    /**
     * Resolve the ref to list the task's own commits from. The configured
     * {@code baseBranch} is only a name — in a fresh worktree it may exist
     * only as a remote-tracking ref ({@code origin/main}) and not locally,
     * which made the old raw {@code main..HEAD} fail and silently drop every
     * commit the task added. We probe the local branch, the remote-tracking
     * branch, then the detected default branch, and diff from the merge-base
     * so a base branch that has advanced past the cut point neither hides the
     * task's commits nor over-includes the base's. Null when nothing resolves.
     */
    /**
     * The base to diff the task against: the live merge-base of HEAD and the
     * branch the worktree was cut from (its configured base branch, resolved
     * to {@code origin/<base>} / the remote default). Computing it live tracks
     * the real fork point even after the branch is rebased onto a newer base —
     * a pinned cut-point SHA would go stale on a rebase and sweep in every
     * commit that landed in between (the "35 commits for a 2-commit task"
     * symptom). Null when no base branch ref resolves.
     */
    private String taskDiffBase(Path cwd, Task task)
            throws IOException, InterruptedException
    {
        return resolveCommitBase(cwd, task);
    }

    private String resolveCommitBase(Path cwd, Task task)
            throws IOException, InterruptedException
    {
        return git.resolveCommitBase(cwd, task == null ? null : task.baseBranch());
    }

    /** Per-file rollup for one commit (path + status + +/-) so the
     *  Commits tab can render a sub-list inside an expanded commit. */
    public List<GitRunner.CommitFileChange> listCommitFiles(String threadId, String taskId, String sha)
    {
        requireNonNull(sha, "sha is null");
        Thread thread = requireTask(threadId);
        return runGit("list files for commit " + sha + " (thread " + threadId + ")",
                () -> git.commitFiles(agentCwd(thread, taskId), sha));
    }

    /** Unified diff for one file at one commit. */
    public String getCommitDiff(String threadId, String taskId, String sha, String path)
    {
        requireNonNull(sha, "sha is null");
        requireNonNull(path, "path is null");
        Thread thread = requireTask(threadId);
        return runGit("diff " + path + " at " + sha + " (thread " + threadId + ")",
                () -> git.commitFileDiff(agentCwd(thread, taskId), sha, path, DIFF_MAX_BYTES));
    }

    /**
     * The task's full diff against its base branch
     * ({@code git diff <base>..HEAD}), one {@link TaskDiffFile} per
     * changed file, shaped like the PR review's {@code DiffFileDto} so
     * the frontend renders it with the same component. Per-file
     * additions/deletions are counted from the patch ({@link GitRunner}'s
     * range listing reports 0 for those). Empty when the worktree was
     * reaped or no base ref resolves.
     */
    public List<TaskDiffFile> taskCumulativeDiff(String threadId, String taskId)
    {
        Thread thread = requireTask(threadId);
        Task task = resolveSurfaceTask(threadId, taskId).orElse(null);
        Optional<Path> cwd = existingAgentCwd(thread, taskId);
        if (cwd.isEmpty()) {
            // Worktree reaped (PR merged) — serve the PR's full diff from GitHub.
            return taskPrLocator(task)
                    .map(pr -> toTaskDiffFiles(
                            pullRequests.getPullRequestDiffFiles(pr.repoFullName(), pr.number())))
                    .orElseGet(List::of);
        }
        return runGit("build cumulative diff for " + threadId, () -> {
            String base = taskDiffBase(cwd.get(), task);
            if (base == null) {
                return List.<TaskDiffFile>of();
            }
            // base → working tree (committed + uncommitted + untracked), so the
            // diff isn't blank when the agent edited but hasn't committed yet.
            List<TaskDiffFile> out = new ArrayList<>();
            for (GitRunner.CommitFileChange f : git.effectiveFiles(cwd.get(), base)) {
                String patch = git.effectiveFileDiff(cwd.get(), base, f.path(), DIFF_MAX_BYTES);
                int[] counts = countDiffLines(patch);
                out.add(new TaskDiffFile(
                        f.path(), statusWord(f.status()), counts[0], counts[1], patch));
            }
            return List.copyOf(out);
        });
    }

    /**
     * One commit's diff as {@link TaskDiffFile} rows (same shape as
     * {@link #taskCumulativeDiff}). {@code git show} already carries the
     * per-file additions/deletions, so those are used directly rather
     * than recounted from the patch. Empty when the worktree was reaped.
     */
    public List<TaskDiffFile> taskCommitDiffFiles(String threadId, String taskId, String sha)
    {
        requireNonNull(sha, "sha is null");
        Thread thread = requireTask(threadId);
        Optional<Path> cwd = existingAgentCwd(thread, taskId);
        if (cwd.isEmpty()) {
            // Worktree reaped (PR merged) — serve this commit's diff from GitHub.
            return taskPrLocator(resolveSurfaceTask(threadId, taskId).orElse(null))
                    .map(pr -> toTaskDiffFiles(
                            pullRequests.getCommitDiffFiles(pr.repoFullName(), pr.number(), sha)))
                    .orElseGet(List::of);
        }
        return runGit("build commit diff " + sha + " for " + threadId, () -> {
            List<TaskDiffFile> out = new ArrayList<>();
            for (GitRunner.CommitFileChange f : git.commitFiles(cwd.get(), sha)) {
                String patch = git.commitFileDiff(cwd.get(), sha, f.path(), DIFF_MAX_BYTES);
                out.add(new TaskDiffFile(
                        f.path(), statusWord(f.status()), f.additions(), f.deletions(), patch));
            }
            return List.copyOf(out);
        });
    }

    /** Full file content from the task worktree, split into lines for
     *  expanding collapsed unchanged regions in the diff viewer. */
    public List<String> taskFileBlobLines(String threadId, String taskId, String path)
    {
        requireNonNull(path, "path is null");
        Thread thread = requireTask(threadId);
        Task task = resolveSurfaceTask(threadId, taskId).orElse(null);
        Optional<Path> cwd = existingAgentCwd(thread, taskId);
        if (cwd.isEmpty()) {
            return taskPrLocator(task)
                    .map(pr -> {
                        List<PullRequestCommit> commits = pullRequests.getPullRequestCommits(pr.repoFullName(), pr.number());
                        String headSha = commits.stream()
                                .map(PullRequestCommit::sha)
                                .filter(sha -> sha != null && !sha.isBlank())
                                .reduce((first, second) -> second)
                                .orElse(null);
                        if (headSha == null) {
                            throw new ResponseStatusException(HttpStatusCode.valueOf(404), "task worktree is not available");
                        }
                        return pullRequests.getFileBlobLines(pr.repoFullName(), path, headSha);
                    })
                    .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "task worktree is not available"));
        }
        return runGit("read file " + path + " for " + threadId, () -> {
            Path root = cwd.get().toRealPath().normalize();
            Path target = root.resolve(path).normalize();
            if (!target.startsWith(root)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400), "path escapes task worktree");
            }
            if (!Files.exists(target)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), "file is not available");
            }
            Path realTarget = target.toRealPath().normalize();
            if (!realTarget.startsWith(root) || !Files.isRegularFile(realTarget)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), "file is not available");
            }
            return splitLines(Files.readString(realTarget, StandardCharsets.UTF_8));
        });
    }

    /** The thread's task PR target ({@code owner/repo} + number), parsed
     *  from the task's {@code linkedPrRef} ({@code owner/repo#number}). Used
     *  to serve a merged task's diff/commits from GitHub once its worktree is
     *  gone. Empty when the task has no linked PR. */
    private Optional<PrLocator> taskPrLocator(Task task)
    {
        if (task == null || task.linkedPrRef() == null) {
            return Optional.empty();
        }
        return PullRequestRef.parse(task.linkedPrRef())
                .map(ref -> new PrLocator(ref.repoRef().fullName(), ref.number()));
    }

    private static List<TaskDiffFile> toTaskDiffFiles(List<DiffFile> files)
    {
        return files.stream()
                .map(f -> new TaskDiffFile(
                        f.filename(), f.status(), f.additions(), f.deletions(), f.patch()))
                .toList();
    }

    private static GitRunner.CommitEntry toCommitEntry(PullRequestCommit c)
    {
        String sha = c.sha() == null ? "" : c.sha();
        String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
        String subject = c.message() == null ? "" : c.message().split("\n", 2)[0];
        String authoredAt = c.authoredAt() == null ? null : c.authoredAt().toString();
        String committedAt = c.committedAt() == null ? authoredAt : c.committedAt().toString();
        String author = c.authorName() != null ? c.authorName() : c.authorLogin();
        return new GitRunner.CommitEntry(
                sha, shortSha, author, null, authoredAt, committedAt, subject);
    }

    private static List<String> splitLines(String text)
    {
        String[] lines = text.split("\n", -1);
        int len = lines.length;
        if (len > 0 && lines[len - 1].isEmpty()) {
            len -= 1;
        }
        return Arrays.stream(lines, 0, len).toList();
    }

    /** Owner/repo + number target for a task's linked PR. */
    private record PrLocator(String repoFullName, int number) {}

    /** Count added/removed lines in a unified diff: a line starting
     *  with '+' (but not the "+++" file header) is an addition, '-'
     *  (but not "---") a deletion. Returns {@code [additions, deletions]}. */
    private static int[] countDiffLines(String patch)
    {
        int adds = 0;
        int dels = 0;
        if (patch != null) {
            for (String line : patch.split("\n", -1)) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    adds++;
                }
                else if (line.startsWith("-") && !line.startsWith("---")) {
                    dels++;
                }
            }
        }
        return new int[] {adds, dels};
    }

    /** Map a git name-status letter to the PR review's status word so
     *  the shared diff component reads them identically. */
    private static String statusWord(String letter)
    {
        if (letter == null || letter.isEmpty()) {
            return "modified";
        }
        return switch (letter.charAt(0)) {
            case 'A' -> "added";
            case 'D' -> "removed";
            case 'R' -> "renamed";
            case 'C' -> "copied";
            default -> "modified";
        };
    }

    private Thread requireTask(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        return store.findThreadById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "no thread: " + threadId));
    }

    /** The task a thread-scoped read or turn operates on: the explicit
     *  {@code taskId} the surface passed, else the thread's latest task.
     *  The latest-task fallback covers the resume-from-terminal path — a
     *  reopened thread whose work went terminal still has its worktree on
     *  disk, and the diff / commits surfaces should keep showing what was
     *  shipped. We never guess a thread-level "active task": with parallel
     *  tasks per thread the surface knows which one it means. */
    private Optional<Task> resolveSurfaceTask(String threadId, String taskId)
    {
        Optional<Task> selected;
        if (taskId != null && !taskId.isBlank()) {
            selected = taskStore.findTaskById(taskId);
        }
        else {
            selected = taskStore.findLatestTaskForThread(threadId);
        }
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        Task task = selected.orElseThrow();
        if (!threadId.equals(task.threadId())) {
            // Do not disclose or read a sibling Trunk's worktree merely
            // because its Task id was supplied to a thread-scoped endpoint.
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "no task: " + task.id());
        }
        if (!taskStore.isV2Task(task.id())) {
            return Optional.of(task);
        }
        if (v2TaskProjection == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Task read projection is not configured");
        }
        return Optional.of(v2TaskProjection.project(task));
    }

    private Path agentCwd(Thread thread, String taskId)
    {
        Task task = resolveSurfaceTask(thread.id(), taskId).orElse(null);
        if (task == null || task.agentCwd() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "thread " + thread.id() + " has no task with a working dir");
        }
        return Path.of(task.agentCwd());
    }

    /** {@link #agentCwd} narrowed to a directory that still exists. Empty
     *  when the worktree has been reaped (a merged task's worktree is
     *  deleted), so read-only commit/diff surfaces can return nothing
     *  rather than failing on a missing directory. */
    private Optional<Path> existingAgentCwd(Thread thread, String taskId)
    {
        Path cwd = agentCwd(thread, taskId);
        return Files.isDirectory(cwd) ? Optional.of(cwd) : Optional.empty();
    }

    private static List<String> distinctCopy(List<String> values)
    {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /** Surface a permission prompt in the conversation pane. Called
     *  by the MCP controller when Claude's {@code approval_prompt}
     *  tool fires. */
    public void notifyPermissionRequested(
            String threadId, String agentKey, String callId, String toolName, String summary)
    {
        throw typedPermissionRequired(threadId);
    }

    /** Applies the user's decision to a pending permission prompt.
     *
     *  @return whether the decision actually resolved a pending prompt —
     *  false means it already timed out or was already decided, so the
     *  caller should tell the user their click had no effect. */
    public boolean decide(String threadId, String callId, PermissionDecision decision)
    {
        return decide(threadId, callId, decision, null, null);
    }

    /** Applies a decision and, when requested, grants a per-tool budget to
     *  the same Task agent that raised the prompt. The agent key must be
     *  captured before {@link ThreadAgent#decide} clears the gate entry. */
    public boolean decide(
            String threadId,
            String callId,
            PermissionDecision decision,
            String preApproveToolName,
            Integer preApproveCount)
    {
        throw typedPermissionRequired(threadId);
    }

    /** Called from the MCP hot path — quiet (returns
     *  {@link OptionalInt#empty()}) if the session is gone instead of
     *  throwing, since a stale prompt shouldn't take down the
     *  controller. The returned int is the remaining budget after the
     *  consumption ({@code -1} for an ALWAYS grant); empty means the
     *  call should fall through to the normal user prompt. */
    public OptionalInt tryConsumeToolBudget(String threadId, String agentKey, String toolName)
    {
        throw typedPermissionRequired(threadId);
    }

    /** Persist + publish a {@code permission_auto_allowed} notice so
     *  the conversation pane can show the user which tool was auto-
     *  approved by their pre-approval budget, and how many slots are
     *  left. */
    public void notifyPermissionAutoAllowed(
            String threadId, String agentKey, String callId, String toolName, int remaining)
    {
        throw typedPermissionRequired(threadId);
    }

    /** Subscribe to live events. The returned {@link Runnable}
     *  unsubscribes — controllers wire it to the SSE
     *  {@code onCompletion}/{@code onTimeout} callbacks. */
    public Runnable subscribeTrunk(String threadId, Consumer<StreamEvent> listener)
    {
        Thread thread = requireTask(threadId);
        requireTrunkThread(thread);
        requireV2Trunk(thread);
        return requireV2ThreadControls().subscribe(thread.id(), listener);
    }

    /** The exact agent that issued an MCP call, by its {@code agentKey}
     *  (Task id or the reserved trunk key). Routing
     *  permission prompts / decisions this way lands them in the stage that
     *  raised them instead of a thread-level "active session" guess. */
    private ResponseStatusException typedPermissionRequired(String threadId)
    {
        requireTask(threadId);
        return new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "Legacy permission sessions are retired; use the typed V2 execution endpoint");
    }

    private static void requireTrunkThread(Thread thread)
    {
        if (thread.kind() == ThreadKind.BRAIN_AGENT) {
            throw new IllegalArgumentException(
                    "thread " + thread.id() + " is a task brain, not a trunk");
        }
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
            ThreadFlow flow,
            /** Owning workspace's id — required. The thread row lands
             *  here and the workspace-scoped lists filter by it. */
            String workspaceId,
            /** Optional per-thread reasoning-effort override. The creation
             *  path normalises its engine fields to the frozen plan engine;
             *  null inherits the engine's own effort. */
            WorkModel workModel,
            /** Optional trunk-supplied {@code PlanResult} (raw JSON). When
             *  present it seeds the new PlanStage's first {@code PLAN_RECORDED}
             *  event with {@code source=trunk}; the brain then validates or
             *  revises it. Null for a task cut without a prior plan. */
            JsonNode trunkPlan,
            /** Defer the planning kickoff: the PlanStage still opens at
             *  creation, but the brain's planning turn is <em>not</em>
             *  started. Retained from the retired task-queue path; every
             *  current creation leaves it false and plans immediately. */
            boolean deferPlanKickoff,
            /** Immutable creator provenance copied to the Task row. */
            String origin,
            /** Optional user remark attached to the trunk itself. */
            String description)
    {
        public NewTaskRequest
        {
            origin = origin == null || origin.isBlank() ? Task.ORIGIN_USER : origin.strip();
            description = description == null || description.isBlank() ? null : description.strip();
        }

        /** Backwards-compatible full constructor predating descriptions. */
        public NewTaskRequest(
                ThreadKind kind, String provider, String model, String title,
                String workingDir, String branchName, String initialPrompt,
                List<String> initialGroupIds, String taskType, Integer linkedPrNumber,
                Integer linkedIssueNumber, ThreadFlow flow, String workspaceId,
                WorkModel workModel, JsonNode trunkPlan, boolean deferPlanKickoff,
                String origin)
        {
            this(kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, trunkPlan, deferPlanKickoff, origin, null);
        }

        /** Backwards-compatible constructor for the common no-trunk-plan
         *  path — leaves {@code trunkPlan} null so existing callers (and
         *  request bodies that omit it) are unchanged. */
        public NewTaskRequest(
                ThreadKind kind, String provider, String model, String title,
                String workingDir, String branchName, String initialPrompt,
                List<String> initialGroupIds, String taskType, Integer linkedPrNumber,
                Integer linkedIssueNumber, ThreadFlow flow, String workspaceId,
                WorkModel workModel)
        {
            this(kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, null, false, Task.ORIGIN_USER, null);
        }

        /** Constructor for callers that supply a trunk plan but plan
         *  immediately (the {@code create_task} path) — leaves
         *  {@code deferPlanKickoff} false. */
        public NewTaskRequest(
                ThreadKind kind, String provider, String model, String title,
                String workingDir, String branchName, String initialPrompt,
                List<String> initialGroupIds, String taskType, Integer linkedPrNumber,
                Integer linkedIssueNumber, ThreadFlow flow, String workspaceId,
                WorkModel workModel, JsonNode trunkPlan)
        {
            this(kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, trunkPlan, false, Task.ORIGIN_USER, null);
        }

        /** Backwards-compatible full constructor predating provenance. */
        public NewTaskRequest(
                ThreadKind kind, String provider, String model, String title,
                String workingDir, String branchName, String initialPrompt,
                List<String> initialGroupIds, String taskType, Integer linkedPrNumber,
                Integer linkedIssueNumber, ThreadFlow flow, String workspaceId,
                WorkModel workModel, JsonNode trunkPlan, boolean deferPlanKickoff)
        {
            this(kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, trunkPlan, deferPlanKickoff, Task.ORIGIN_USER, null);
        }

        public NewTaskRequest withOrigin(String origin)
        {
            return new NewTaskRequest(
                    kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, trunkPlan, deferPlanKickoff, origin, description);
        }

        public NewTaskRequest withDescription(String description)
        {
            return new NewTaskRequest(
                    kind, provider, model, title, workingDir, branchName, initialPrompt,
                    initialGroupIds, taskType, linkedPrNumber, linkedIssueNumber, flow,
                    workspaceId, workModel, trunkPlan, deferPlanKickoff, origin, description);
        }

        public NewTaskRequest withEngine(WorkModel engine)
        {
            requireNonNull(engine, "engine is null");
            ThreadKind engineKind = engine.kind() == WorkModelKind.API
                    ? ThreadKind.LOGIC_LOOP
                    : ThreadKind.CLI_AGENT;
            WorkModel scopedWorkModel = workModel == null
                    ? null
                    : new WorkModel(
                            engine.kind(), engine.agentOrProvider(), engine.model(),
                            engine.account(), workModel.reasoningEffort());
            return new NewTaskRequest(
                    engineKind, engine.agentOrProvider(), engine.model(), title,
                    workingDir, branchName, initialPrompt, initialGroupIds, taskType,
                    linkedPrNumber, linkedIssueNumber, flow, workspaceId, scopedWorkModel,
                    trunkPlan, deferPlanKickoff, origin, description);
        }
    }

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

    /**
     * One changed file in a task diff, shaped to match the PR review's
     * {@code DiffFileDto} ({@code filename, status, additions, deletions,
     * patch}) so the frontend renders both with the same component.
     * {@code status} is the PR's word ({@code added/removed/renamed/
     * copied/modified}), not git's single letter.
     */
    public record TaskDiffFile(
            String filename,
            String status,
            int additions,
            int deletions,
            String patch) {}
}
