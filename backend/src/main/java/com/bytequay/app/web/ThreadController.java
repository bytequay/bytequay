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

import com.bytequay.app.beans.workmodel.ResolvedWorkModelResponse;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.domain.ConvIndexPage;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.service.inspector.AssembledContext;
import com.bytequay.app.service.inspector.ContextAssembler;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.CheckpointTrigger;
import com.bytequay.app.service.threads.ConvIndexService;
import com.bytequay.app.service.threads.MessageAttachments;
import com.bytequay.app.service.threads.PrTaskLinkService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workmodel.ScopeWorkModel;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Local REST surface for the Tasks page.
 *
 * <p>Exposes one resource per primitive in {@link ThreadService}:
 * list/create on {@code /api/threads}, detail/history under
 * {@code /api/threads/{id}}, mutating verbs as POST sub-paths, and a
 * single SSE stream that the frontend's thread-detail view subscribes
 * to for live events.
 */
@RestController
@RequestMapping("/api/threads")
public class ThreadController
{
    private static final Logger log = LoggerFactory.getLogger(ThreadController.class);

    /** Per-page cap; matches the design doc's "show ~50 most recent
     *  threads" target for the list view. */
    private static final int DEFAULT_LIMIT = 50;
    /** Active scheduler turns are small rows; 200 covers busy groups
     *  without returning the whole historical table. */
    private static final int DEFAULT_ACTIVE_TURN_LIMIT = 200;

    /** Six hours — long enough for an unattended overnight run, short
     *  enough that abandoned browser tabs don't leak the stream. */
    private static final long STREAM_TIMEOUT_MS = 6L * 60L * 60L * 1000L;

    /** Default page size for the conversation-index window. Matches
     *  the doc's "load the last 50 prompts" target. */
    private static final int DEFAULT_INDEX_LIMIT = 50;

    private final ThreadService threads;
    private final ConvIndexService convIndex;
    private final ThreadCheckpointStore checkpoints;
    private final CheckpointTrigger checkpointTrigger;
    private final ContextAssembler contextAssembler;
    private final WorkModelResolver workModelResolver;
    private final PrTaskLinkService prTaskLink;
    private final TaskStore taskStore;
    private final ChatAttachmentStore attachmentStore;
    private final ObjectMapper mapper;
    private V2UserWaitService v2Waits;
    private ReasoningEffortService reasoningEfforts;

    public ThreadController(
            ThreadService threads,
            ConvIndexService convIndex,
            ThreadCheckpointStore checkpoints,
            CheckpointTrigger checkpointTrigger,
            ContextAssembler contextAssembler,
            WorkModelResolver workModelResolver,
            PrTaskLinkService prTaskLink,
            TaskStore taskStore,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.convIndex = requireNonNull(convIndex, "convIndex is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.contextAssembler = requireNonNull(contextAssembler, "contextAssembler is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
        this.prTaskLink = requireNonNull(prTaskLink, "prTaskLink is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.attachmentStore = requireNonNull(attachmentStore, "attachmentStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Autowired(required = false)
    void setV2Waits(V2UserWaitService v2Waits)
    {
        this.v2Waits = requireNonNull(v2Waits, "v2Waits is null");
    }

    @Autowired
    void setReasoningEfforts(ReasoningEffortService reasoningEfforts)
    {
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    /** GET /api/threads?status=RUNNING&limit=50&groupId=...&workspaceId=... */
    @GetMapping
    public List<Thread> list(
            @RequestParam(required = false) ThreadStatus status,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit)
    {
        int cap = Math.min(limit, DEFAULT_LIMIT);
        List<Thread> result;
        if (groupId != null && !groupId.isBlank()) {
            List<Thread> page = threads.listByGroup(groupId, cap);
            result = status == null ? page : page.stream().filter(t -> t.status() == status).toList();
        }
        else {
            boolean wsScoped = workspaceId != null && !workspaceId.isBlank();
            if (status == null) {
                ImmutableList.Builder<Thread> all = ImmutableList.builder();
                for (ThreadStatus s : ThreadStatus.values()) {
                    all.addAll(wsScoped
                            ? threads.listByWorkspaceAndStatus(workspaceId, s, cap)
                            : threads.listByStatus(s, cap));
                }
                result = all.build();
            }
            else {
                result = wsScoped
                        ? threads.listByWorkspaceAndStatus(workspaceId, status, cap)
                        : threads.listByStatus(status, cap);
            }
        }
        // Brain-agent threads are per-task internal children (the read-only
        // planning / brain agent), reached through the task brain view — they
        // must never surface as top-level threads in the workspace list.
        return result.stream()
                .filter(t -> t.kind() != ThreadKind.BRAIN_AGENT)
                .filter(t -> t.flow() != ThreadFlow.REVIEW)
                .toList();
    }

    /** GET /api/threads/turns/active — queued/running turns across threads. */
    @GetMapping("/turns/active")
    public List<ThreadTurn> activeTurns(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_ACTIVE_TURN_LIMIT) int limit)
    {
        return threads.activeTurns(limit);
    }

    /**
     * POST /api/threads — create a 0-Task thread. The thread lands
     * on the trunk (planning); the optional {@code initialPrompt} is
     * routed as a trunk turn and the title is derived from it when
     * omitted. No worktree, no branch, no Task. Use
     * {@code POST /api/threads/{id}/tasks} to materialise a Task
     * later when work turns branch-worthy.
     */
    @PostMapping
    public Thread create(@RequestBody NewTaskBody body)
    {
        requireNonNull(body, "body is null");
        if (body.kind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (body.workspaceId() == null || body.workspaceId().isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        // Resolve all four roles now. The request contains only explicit
        // picker overrides; untouched roles start from the workspace, then
        // the complete model/account choices are frozen on the new trunk.
        WorkModel engine = workModelResolver
                .resolveForWorkspace(body.workspaceId(), SessionAudience.PLAN).choice();
        return threads.createWithEngineOverrides(new ThreadService.NewTaskRequest(
                engine.kind() == WorkModelKind.API ? ThreadKind.LOGIC_LOOP : ThreadKind.CLI_AGENT,
                engine.agentOrProvider(),
                engine.model(),
                body.title(),
                body.workingDir(),
                body.branchName(),
                body.initialPrompt(),
                body.initialGroupIds() == null ? List.of() : body.initialGroupIds(),
                body.taskType(),
                body.linkedPrNumber(),
                body.linkedIssueNumber(),
                /* flow */ null,
                body.workspaceId(),
                ScopeWorkModel.effortOnly(engine, body.workModel()))
                .withDescription(body.description()), body.engines());
    }

    /**
     * POST /api/threads/{id}/tasks — materialise a Task under an
     * existing thread (cuts a dev branch + worktree). The body
     * carries the same shape as the thread-create body but {@code
     * workingDir} is required here. Used by the assign-dev-task
     * action today; the trunk's agent-proposed "looks like it'll
     * touch code, start a task?" prompt will route through here too
     * once it lands.
     */
    @PostMapping("/{id}/tasks")
    public Task materialiseTask(
            @PathVariable String id,
            @RequestBody NewTaskBody body)
    {
        requireNonNull(body, "body is null");
        if (body.kind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (body.workingDir() == null || body.workingDir().isBlank()) {
            throw new IllegalArgumentException("workingDir is required");
        }
        // Author + 1:1-active gate when linking to an existing PR: only
        // your own PR, and only when no active task already owns it.
        // Throws 422 / 409 before the task is cut. Empty = the worktree
        // doesn't map to a watched repo, so there's nothing to link.
        Optional<String> linkPrRef = body.linkedPrNumber() == null
                ? Optional.empty()
                : prTaskLink.assertCanCreateDevTaskForWorktree(
                        body.workingDir(), body.linkedPrNumber());
        Task created = threads.materialiseTask(id, new ThreadService.NewTaskRequest(
                body.kind(),
                body.provider() == null ? "claude-code" : body.provider(),
                body.model(),
                body.title(),
                body.workingDir(),
                body.branchName(),
                body.initialPrompt(),
                body.initialGroupIds() == null ? List.of() : body.initialGroupIds(),
                body.taskType(),
                body.linkedPrNumber(),
                body.linkedIssueNumber(),
                /* flow */ null,
                // materialiseTask doesn't read workspaceId on the request,
                // but the record requires it — surface the body value
                // anyway so a future code path picks it up.
                body.workspaceId(),
                body.workModel(),
                body.trunkPlan()));
        // Permanent task→PR link (drives the 1:1-active index + the PR
        // card's linked-task chip).
        linkPrRef.ifPresent(ref -> taskStore.linkTaskToPr(created.id(), ref));
        return created;
    }

    /** GET /api/threads/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<Thread> get(@PathVariable String id)
    {
        return threads.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/threads/{id}/context?dryRun=true — read-only view of
     * what would be in the trunk turn's prompt right now.
     *
     * <p>{@code dryRun=true} is mandatory. The endpoint exists only
     * for the inspector; there is no "send" mode, and a request
     * without the flag set to true is rejected with 400 to make
     * that contract impossible to ignore.
     *
     * <p>v1 doesn't yet have an agent-session principal model the
     * server can introspect; the spec's "agent sessions denied"
     * rule lands when the principal-tagging infrastructure does.
     * For now this is a local UI surface only — no auth header, no
     * agent-callable path.
     */
    @GetMapping("/{id}/context")
    public AssembledContext context(
            @PathVariable String id,
            @RequestParam(value = "dryRun", required = false) Boolean dryRun)
    {
        if (!Boolean.TRUE.equals(dryRun)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "dryRun=true is required — this endpoint never dispatches");
        }
        return contextAssembler.forThread(id);
    }

    /**
     * PATCH /api/threads/{id} — partial update. Only {@code title} is
     * editable; pass a non-null, non-blank string to rename. Group
     * membership moved to its own endpoints under
     * {@code /api/thread-groups/{id}/members/{threadId}} since one thread
     * can belong to many groups.
     */
    @PatchMapping("/{id}")
    public Thread patch(@PathVariable String id, @RequestBody PatchTaskBody body)
    {
        requireNonNull(body, "body is required");
        return threads.patchTask(id, new ThreadService.TaskPatch(body.title()));
    }

    /**
     * GET /api/threads/{id}/work-model — resolve the effective work
     * model for the thread, returning both the scope's own override
     * (nullable) and the cascade winner with provenance.
     */
    @GetMapping("/{id}/work-model")
    public ResolvedWorkModelResponse getWorkModel(@PathVariable String id)
    {
        Thread thread = threads.find(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no thread: " + id));
        // The resolver derives the trunk audience from the thread's flow, so
        // the pill names the engine that will actually spawn.
        WorkModelResolver.Resolved resolved = workModelResolver.resolveForThread(id);
        return new ResolvedWorkModelResponse(
                thread.workModel(), resolved.choice(), resolved.provenance(),
                threads.isWorkModelAgentLocked(id));
    }

    /**
     * PUT /api/threads/{id}/work-model — set (or clear) the thread's
     * reasoning-effort override. The engine itself is the workspace's
     * call, so any engine fields in the body are ignored; a body with no
     * effort clears the override and the workspace's effort applies.
     * Returns the resolved outcome so the caller does not need a
     * follow-up GET.
     */
    @PutMapping("/{id}/work-model")
    public ResolvedWorkModelResponse setWorkModel(
            @PathVariable String id,
            @RequestBody(required = false) WorkModelBody body)
    {
        WorkModel requested = body == null ? null : body.workModel();
        WorkModel engine = workModelResolver.resolveForThread(id).choice();
        try {
            requireReasoningEfforts().setTrunk(
                    id, engine, ReasoningEffortService.requested(requested));
        }
        catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), failure.getMessage(), failure);
        }
        catch (IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), failure.getMessage(), failure);
        }
        Thread updated = threads.find(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no thread: " + id));
        WorkModelResolver.Resolved resolved = workModelResolver.resolveForThread(id);
        return new ResolvedWorkModelResponse(
                updated.workModel(), resolved.choice(), resolved.provenance(),
                threads.isWorkModelAgentLocked(id));
    }

    private ReasoningEffortService requireReasoningEfforts()
    {
        if (reasoningEfforts == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Reasoning effort controls are not configured");
        }
        return reasoningEfforts;
    }

    /** Request body for {@link #setWorkModel} — wraps the optional
     *  {@link WorkModel} so a {@code null} field maps cleanly to
     *  "clear the override". */
    public record WorkModelBody(WorkModel workModel) {}

    /** GET /api/threads/{id}/messages — full conversation, oldest first. */
    @GetMapping("/{id}/messages")
    public List<ThreadMessage> messages(@PathVariable String id)
    {
        return threads.history(id);
    }

    /**
     * GET /api/threads/{id}/index?cursor=&limit=&direction=
     *
     * <p>Conversation index window. Two modes:
     * <ul>
     *   <li>{@code direction=initial} (default): most-recent
     *       {@code limit} messages plus the user-prompt index entries
     *       derived from them, plus the thread-wide user-prompt count
     *       for the "N of M" header.</li>
     *   <li>{@code direction=before}: messages canonically before
     *       {@code cursor}, oldest-first; used by the "↑ load earlier"
     *       affordance to prepend to the loaded window. A promoted Trunk's
     *       cursor is source-aware and must not be compared numerically.</li>
     * </ul>
     *
     * <p>Both modes return the messages and the derived index entries
     * in <b>one round-trip</b> so the two views can't drift — the
     * design doc explicitly forbids fetching the index without the
     * messages or vice versa for the same window.
     */
    @GetMapping("/{id}/index")
    public ConvIndexPage index(
            @PathVariable String id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_INDEX_LIMIT) int limit,
            @RequestParam(required = false, defaultValue = "initial") String direction)
    {
        if ("before".equalsIgnoreCase(direction)) {
            if (cursor == null) {
                throw new IllegalArgumentException(
                        "cursor is required when direction=before — pass the earliest canonical seq currently loaded");
            }
            return convIndex.backfill(id, cursor, limit);
        }
        return convIndex.initial(id, limit);
    }

    /** GET /api/threads/{id}/checkpoints — every active checkpoint for
     *  the thread. Overall first, then segments by descending seq, so
     *  the sidebar card can render newest-on-top without sorting. */
    @GetMapping("/{id}/checkpoints")
    public List<ThreadCheckpoint> listCheckpoints(@PathVariable String id)
    {
        return checkpoints.listActive(id);
    }

    /** GET /api/threads/{id}/checkpoints/{checkpointId} — single
     *  checkpoint for the detail drawer + the cross-thread seed loader. */
    @GetMapping("/{id}/checkpoints/{checkpointId}")
    public ResponseEntity<ThreadCheckpoint> getCheckpoint(
            @PathVariable String id,
            @PathVariable String checkpointId)
    {
        return checkpoints.findById(checkpointId)
                .filter(cp -> cp.threadId().equals(id))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** POST /api/threads/{id}/checkpoints — manual generate. Returns the
     *  new checkpoint or 204 when there's nothing new since the last
     *  segment (the UI button should be disabled in that state, but
     *  we still need a safe answer if it fires). */
    @PostMapping("/{id}/checkpoints")
    public ResponseEntity<ThreadCheckpoint> generateCheckpoint(@PathVariable String id)
    {
        return checkpointTrigger.manualGenerate(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** GET /api/threads/{id}/checkpoints/status — last scheduler outcome
     *  for the thread. {@code lastError} is null when the most recent
     *  attempt succeeded or hasn't run yet; non-null carries the
     *  failure message (typically "Anthropic API key not configured")
     *  so the rail can render a "summariser disabled" hint instead of
     *  a silent empty list. */
    @GetMapping("/{id}/checkpoints/status")
    public CheckpointStatusResponse checkpointStatus(@PathVariable String id)
    {
        return new CheckpointStatusResponse(checkpointTrigger.lastErrorFor(id).orElse(null));
    }

    public record CheckpointStatusResponse(String lastError) {}

    /** DELETE /api/threads/{id}/checkpoints/{checkpointId} — drop one
     *  per-segment row. The store refuses Overall rows (those are
     *  scheduler-owned and regenerate on the next turn). */
    @DeleteMapping("/{id}/checkpoints/{checkpointId}")
    public ResponseEntity<Void> deleteCheckpoint(
            @PathVariable String id,
            @PathVariable String checkpointId)
    {
        return checkpoints.findById(checkpointId)
                .filter(cp -> cp.threadId().equals(id))
                .map(cp -> {
                    checkpoints.deleteSegment(checkpointId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** GET /api/threads/{id}/turns — recent scheduler turns, newest first. */
    @GetMapping("/{id}/turns")
    public List<ThreadTurn> turns(@PathVariable String id)
    {
        return threads.turns(id);
    }

    /** GET /api/threads/{id}/turn-events — scheduler timeline, newest first. */
    @GetMapping("/{id}/turn-events")
    public List<ThreadTurnEvent> turnEvents(@PathVariable String id)
    {
        return threads.turnEvents(id);
    }

    /** GET /api/threads/{id}/files — per-file rollup, most-recently
     *  touched first. Powers the sidebar's Files touched card. */
    @GetMapping("/{id}/files")
    public List<ThreadFile> files(@PathVariable String id)
    {
        return threads.files(id);
    }

    // ── Tabs: Files (uncommitted) + Commits (since thread started) ─────

    /** GET /api/threads/{id}/working-changes — paths the AI session has
     *  modified but not yet committed. Drives the Files tab. */
    @GetMapping("/{id}/working-changes")
    public List<GitRunner.WorkingTreeFile> workingChanges(
            @PathVariable String id,
            @RequestParam(required = false) String taskId)
    {
        return threads.listWorkingChanges(id, taskId);
    }

    /** GET /api/threads/{id}/working-diff?path=... — unified diff for
     *  one uncommitted file. Capped at 256 KB. */
    @GetMapping("/{id}/working-diff")
    public Map<String, String> workingDiff(
            @PathVariable String id,
            @RequestParam(required = false) String taskId,
            @RequestParam String path)
    {
        return ImmutableMap.of("diff", threads.getWorkingDiff(id, taskId, path));
    }

    /** GET /api/threads/{id}/commits — commits authored since the thread
     *  was created. Drives the Commits tab. */
    @GetMapping("/{id}/commits")
    public List<GitRunner.CommitEntry> commits(
            @PathVariable String id,
            @RequestParam(required = false) String taskId)
    {
        return threads.listTaskCommits(id, taskId);
    }

    /** GET /api/threads/{id}/commits/{sha}/files — per-file rollup for
     *  one of the thread's commits. */
    @GetMapping("/{id}/commits/{sha}/files")
    public List<GitRunner.CommitFileChange> commitFiles(
            @PathVariable String id,
            @RequestParam(required = false) String taskId,
            @PathVariable String sha)
    {
        return threads.listCommitFiles(id, taskId, sha);
    }

    /** GET /api/threads/{id}/commits/{sha}/diff?path=... — unified diff
     *  for one file at one commit. */
    @GetMapping("/{id}/commits/{sha}/diff")
    public Map<String, String> commitDiff(
            @PathVariable String id,
            @RequestParam(required = false) String taskId,
            @PathVariable String sha,
            @RequestParam String path)
    {
        return ImmutableMap.of("diff", threads.getCommitDiff(id, taskId, sha, path));
    }

    /** GET /api/threads/{id}/cumulative-diff — the task's full diff
     *  against its base branch, one {@code DiffFileDto}-shaped row per
     *  file, so the frontend reuses the PR diff component. */
    @GetMapping("/{id}/cumulative-diff")
    public List<ThreadService.TaskDiffFile> cumulativeDiff(
            @PathVariable String id,
            @RequestParam(required = false) String taskId)
    {
        return threads.taskCumulativeDiff(id, taskId);
    }

    /** GET /api/threads/{id}/commits/{sha}/diff-files — one commit's
     *  diff as {@code DiffFileDto}-shaped rows for the shared diff view. */
    @GetMapping("/{id}/commits/{sha}/diff-files")
    public List<ThreadService.TaskDiffFile> commitDiffFiles(
            @PathVariable String id,
            @RequestParam(required = false) String taskId,
            @PathVariable String sha)
    {
        return threads.taskCommitDiffFiles(id, taskId, sha);
    }

    /** GET /api/threads/{id}/fileBlob?taskId=...&path=... — full file
     *  content from the task worktree for expanding unchanged diff lines. */
    @GetMapping("/{id}/fileBlob")
    public Map<String, Object> fileBlob(
            @PathVariable String id,
            @RequestParam String taskId,
            @RequestParam String path)
    {
        return ImmutableMap.of("lines", threads.taskFileBlobLines(id, taskId, path));
    }

    /** POST /api/threads/{id}/messages — send a follow-up turn. */
    @PostMapping("/{id}/messages")
    public Map<String, String> send(@PathVariable String id, @RequestBody SendBody body)
    {
        requireNonNull(body, "body is null");
        if (body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input is required");
        }
        if (body.taskId() == null || body.taskId().isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        String input = encodeWithImages(id, body.input(), body.images());
        String turnId = threads.send(id, body.taskId(), input);
        return ImmutableMap.of("status", "queued", "turnId", turnId);
    }

    /** POST /api/threads/{id}/trunk-turns — send a trunk-scope (planning)
     *  turn. Differs from {@code /messages} in that the persisted row is
     *  tagged {@code task_id = null}, routing the agent through the
     *  trunk-mode runtime (no worktree lease, planning altitude). */
    @PostMapping("/{id}/trunk-turns")
    public Map<String, String> sendTrunk(@PathVariable String id, @RequestBody SendBody body)
    {
        requireNonNull(body, "body is null");
        if (body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input is required");
        }
        String input = encodeWithImages(id, body.input(), body.images());
        String turnId = threads.sendTrunk(id, input);
        return ImmutableMap.of("status", "queued", "turnId", turnId);
    }

    /** GET /api/threads/{id}/attachments?path=... — serves a pasted image
     *  back for a message's thumbnail. {@code path} is one of the paths a
     *  {@code text} message's {@code contentJson.images} already carries.
     *  {@code id} isn't used for lookup (see {@code ChatAttachmentStore.read}'s
     *  doc) — kept in the URL so it reads like every other thread sub-resource. */
    @GetMapping("/{id}/attachments")
    public ResponseEntity<byte[]> attachment(@PathVariable String id, @RequestParam String path)
    {
        ChatAttachmentStore.Attachment attachment = attachmentStore.read(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.mimeType()))
                .body(attachment.bytes());
    }

    /** Saves any pasted-image data URLs to disk and folds their paths into
     *  the plain-text turn input via {@link MessageAttachments} — see its
     *  doc for why this rides inside the {@code String} rather than a new
     *  column. A plain-text send (the common case) passes through untouched. */
    private String encodeWithImages(String threadId, String text, List<String> imageDataUrls)
    {
        List<String> paths = attachmentStore.save(threadId, imageDataUrls);
        return MessageAttachments.encode(mapper, text, paths);
    }

    @PostMapping("/{id}/interrupt")
    public Map<String, String> interrupt(
            @PathVariable String id,
            @RequestParam(required = false) String turnId)
    {
        threads.interruptTrunk(id, turnId);
        return ImmutableMap.of("status", "interrupted");
    }

    /** POST /api/threads/{id}/jump-in — take control of the thread
     *  away from any in-flight headless run. Interrupts the live
     *  session if one exists, releases the active task's worktree
     *  lease, and marks parked notifications for the thread as read.
     *  Returns the updated thread so the UI doesn't need a separate
     *  GET to refresh the card. */
    @PostMapping("/{id}/jump-in")
    public Thread jumpIn(@PathVariable String id)
    {
        return threads.jumpIn(id);
    }

    @PostMapping("/{id}/resume")
    public Map<String, String> resume(@PathVariable String id)
    {
        threads.resume(id);
        return ImmutableMap.of("status", "resumed");
    }

    @PostMapping("/{id}/stop")
    public Map<String, String> stop(@PathVariable String id)
    {
        threads.stop(id);
        return ImmutableMap.of("status", "stopped");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id)
    {
        threads.delete(id);
        return ImmutableMap.of("status", "deleted");
    }

    /** Pre-flight eligibility — returns {@code deletable: true} when
     *  the thread can be removed, or a human-readable {@code reason}
     *  when blocked (e.g. shipped tasks). The trunk's Delete button
     *  calls this on mount to surface the block reason inline. */
    @GetMapping("/{id}/delete-eligibility")
    public Map<String, Object> deleteEligibility(@PathVariable String id)
    {
        Optional<String> blocked = threads.deleteBlockedReason(id);
        if (blocked.isPresent()) {
            return ImmutableMap.of(
                    "deletable", false,
                    "reason", blocked.get());
        }
        return ImmutableMap.of("deletable", true);
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
        Optional<V2UserWaitStore.PermissionRequest> typedPermission =
                v2Waits == null
                        ? Optional.empty()
                        : v2Waits.findPermission(body.callId());
        if (typedPermission.isPresent()) {
            int expectedRevision = body.expectedRevision() == null
                    ? 0 : body.expectedRevision();
            V2UserWaitStore.PermissionRequest request = v2Waits
                    .findPermissionForTrunk(id, body.callId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404),
                            "permission request does not belong to thread"));
            V2UserWaitStore.PermissionChoice choice =
                    permissionChoice(body, request.owner().kind());
            V2UserWaitStore.PermissionResolution resolved =
                    v2Waits.answerPermission(
                            id, body.callId(), expectedRevision, choice,
                            "local-user");
            return ImmutableMap.of(
                    "status", resolved.accepted()
                            ? "recorded" : "already_resolved");
        }
        // Optional pre-approval rider — when the user clicks "Allow next
        // 5 / 10 / 50 / Always", the service captures the issuing agent
        // before resolving the gate, then stores the budget on that same
        // Task session. -1 is the "always" sentinel.
        boolean resolved = threads.decide(
                id, body.callId(), body.decision(),
                body.preApproveToolName(), body.preApproveCount());
        // "already_resolved" means this specific prompt had already timed
        // out (or was already decided) before the click landed — the
        // frontend surfaces this distinctly instead of a silent no-op.
        return ImmutableMap.of("status", resolved ? "recorded" : "already_resolved");
    }

    @GetMapping("/{id}/permissions")
    public List<PermissionRequestDto> openPermissions(@PathVariable String id)
    {
        if (v2Waits == null) {
            return List.of();
        }
        return v2Waits.listOpenPermissions(id).stream()
                .map(PermissionRequestDto::from)
                .toList();
    }

    private V2UserWaitStore.PermissionChoice permissionChoice(
            DecisionBody body, DispatchTicket.OwnerKind ownerKind)
    {
        var answer = mapper.createObjectNode();
        answer.put("decision", body.decision().name());
        if (body.preApproveCount() != null) {
            answer.put("count", body.preApproveCount());
        }
        if (body.permissionScope() != null) {
            answer.put("scope", body.permissionScope());
        }
        String evidence = answer.toString();
        if (body.decision() == PermissionDecision.DENY) {
            return V2UserWaitStore.PermissionChoice.deny(evidence);
        }
        Integer count = body.preApproveCount();
        if (count == null) {
            return V2UserWaitStore.PermissionChoice.allowOnce(evidence);
        }
        if (count > 0) {
            return V2UserWaitStore.PermissionChoice.allowNext(count, evidence);
        }
        if (count != -1) {
            throw new IllegalArgumentException(
                    "preApproveCount must be positive or -1");
        }
        boolean repository = "REPOSITORY".equalsIgnoreCase(body.permissionScope())
                || ownerKind == DispatchTicket.OwnerKind.THREAD_TURN
                || ownerKind == DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN;
        return repository
                ? V2UserWaitStore.PermissionChoice.alwaysRepository(evidence)
                : V2UserWaitStore.PermissionChoice.alwaysTask(evidence);
    }

    /**
     * GET /api/threads/{id}/stream — Server-Sent Events of every
     * {@link com.bytequay.app.domain.StreamEvent} the session emits
     * after subscription. The renderer uses the {@code event:}
     * channel to switch on shape; all payloads are JSON.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id)
    {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Runnable unsubscribe = threads.subscribeTrunk(id, event -> {
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
            log.debug("SSE error on thread {} stream: {}", id, t.getMessage());
            unsubscribe.run();
        });
        return emitter;
    }

    /**
     * POST body for {@link #create}.
     *
     * @param initialGroupIds optional group ids to pre-pin the new thread into.
     * @param taskType free-form thread type.
     * @param linkedPrNumber optional GitHub PR number to link.
     * @param linkedIssueNumber optional GitHub issue number to link.
     */
    public record NewTaskBody(
            ThreadKind kind,
            String provider,
            String model,
            String title,
            String description,
            String workingDir,
            String branchName,
            String initialPrompt,
            List<String> initialGroupIds,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            /** Owning workspace id — required. Threads belong to a
             *  workspace; the service rejects the create when null/blank. */
            String workspaceId,
            /** Optional per-thread work-model override set at creation
             *  time. Null inherits from the workspace. */
            WorkModel workModel,
            /** Optional trunk-supplied {@code PlanResult} JSON. When present,
             *  seeds the new PlanStage's first plan ({@code source=trunk}) so
             *  the brain validates or revises it instead of planning cold. */
            JsonNode trunkPlan,
            /** Optional per-session-kind engine overrides from the create dialog,
             *  keyed by audience ({@code plan} / {@code dev} / {@code review}
             *  / {@code ci-fix}) and valued with the settings page's picker
             *  ids. Absent kinds copy the workspace's effective choice at
             *  creation. All four choices are then immutable for the thread;
             *  materialising a task keeps that snapshot. */
            Map<String, String> engines) {}

    /** {@code images}: pasted-screenshot data URLs (e.g. {@code
     *  data:image/png;base64,...}) from the composer's clipboard-paste
     *  handler. Optional/omittable — a plain-text send passes null/empty. */
    public record SendBody(String taskId, String input, List<String> images) {}

    public record DecisionBody(
            String callId,
            PermissionDecision decision,
            String preApproveToolName,
            Integer preApproveCount,
            Integer expectedRevision,
            String permissionScope) {}

    public record PermissionRequestDto(
            String id,
            String callId,
            String ownerKind,
            String turnId,
            String operationId,
            String capability,
            String toolName,
            String parametersJson,
            String state,
            int answerRevision,
            long requestedAt)
    {
        static PermissionRequestDto from(
                V2UserWaitStore.PermissionRequest request)
        {
            return new PermissionRequestDto(
                    request.id(), request.callId(),
                    request.owner().kind().name(), request.owner().turnId(),
                    request.owner().operationId(), request.capability(),
                    request.toolName(), request.parametersJson(), request.state(),
                    request.answerRevision(),
                    request.requestedAt().toEpochMilli());
        }
    }

    public record PatchTaskBody(String title) {}
}
