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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.QueuedTaskStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

/**
 * Bridges the {@link Thread} domain record (which still exposes the
 * execution fields callers depend on — worktreePath, branchName,
 * processPid, …) against the post-V72 split schema where those fields
 * live on the {@code tasks} row, not on {@code threads}.
 *
 * <p>Saves split the payload: conversation columns go to {@code threads},
 * execution columns flow to the active task (created with seq=1 the
 * first time, updated thereafter). Reads merge the two back into the
 * fat {@link Thread} record so existing callers see the same shape.
 *
 * <p>This hidden coupling is a temporary bridge while the call sites
 * still read {@code thread.worktreePath()} etc. directly; a follow-up
 * commit can shrink the {@link Thread} record once those callers move
 * to an explicit {@code TaskService.findActiveTask(threadId)} lookup.
 */
@Component
class SqliteThreadStore
        implements ThreadStore
{
    private final ThreadJpaRepository threads;
    private final ThreadMessageJpaRepository messages;
    private final TaskStore taskStore;
    private final ObjectMapper objectMapper;

    SqliteThreadStore(
            ThreadJpaRepository threads,
            ThreadMessageJpaRepository messages,
            TaskStore taskStore,
            ObjectMapper objectMapper)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.messages = requireNonNull(messages, "messages is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    /** Ambient workspace every new thread joins. Multi-workspace
     *  creation will route this through the create request later. */
    private static final String DEFAULT_WORKSPACE_ID = "ws-default";

    @Override
    @Transactional
    public void saveThread(Thread thread)
    {
        Optional<ThreadEntity> existing = threads.findById(thread.id());
        ThreadEntity entity = existing.orElseGet(ThreadEntity::new);
        entity.setId(thread.id());
        entity.setKind(thread.kind().name());
        entity.setProvider(thread.provider());
        entity.setAgentSessionId(thread.agentSessionId());
        entity.setTitle(thread.title());
        entity.setStatus(thread.status().name());
        entity.setModel(thread.model());
        entity.setCostUsdMilli(thread.costUsdMilli());
        entity.setTokensIn(thread.tokensIn());
        entity.setTokensOut(thread.tokensOut());
        entity.setCreatedAtMs(thread.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(thread.updatedAt().toEpochMilli());
        entity.setEndedAtMs(thread.endedAt() == null ? null : thread.endedAt().toEpochMilli());
        entity.setErrorMessage(thread.errorMessage());
        // Honour the caller's workspace assignment when set. Existing
        // rows keep their stored workspaceId — the create path is what
        // routes the new thread into the right workspace; later
        // saveThread calls just update mutable fields. The "ws-default"
        // fallback only kicks in for legacy callers that left the
        // field null on a brand-new row.
        if (existing.isEmpty()) {
            String desired = thread.workspaceId();
            entity.setWorkspaceId(desired != null && !desired.isBlank()
                    ? desired
                    : DEFAULT_WORKSPACE_ID);
        }
        // Flow is set-once: write it on INSERT, refuse to silently
        // flip it on UPDATE. The build vs review discriminator is a
        // structural property of the thread; if a caller wants to
        // change it, that's a different thread.
        if (existing.isEmpty()) {
            ThreadFlow flow = thread.flow() == null ? ThreadFlow.BUILD : thread.flow();
            entity.setFlow(flow.dbValue());
            // parallel_slots is structural (v1 invariant 1) — write it on
            // INSERT only. queue_json is deliberately NOT written here:
            // it's entity-managed via updateThreadQueue so a full-row
            // saveThread can't clobber a concurrent queue edit. The column
            // default '[]' covers a brand-new row.
            entity.setParallelSlots(thread.parallelSlots() < 1 ? 1 : thread.parallelSlots());
        }
        else if (thread.flow() != null
                && !thread.flow().dbValue().equals(entity.getFlow())) {
            throw new IllegalStateException(
                    "thread.flow is set-once; cannot flip thread " + thread.id()
                            + " from " + entity.getFlow() + " to " + thread.flow().dbValue());
        }
        entity.setWorkModelJson(serialiseWorkModel(thread.workModel()));
        entity.setParentReviewPassId(thread.parentReviewPassId());
        threads.save(entity);

        // Mirror Thread-level state (status, running cost / tokens,
        // endedAt, errorMessage) onto the active task so a reader of
        // the task row sees a consistent picture. branchName /
        // worktreePath / workingDir / linked PR/issue / taskType
        // live on the task and are no longer overridden via Thread —
        // callers that want to change them go through TaskStore
        // directly. ThreadService.create materialises the first task
        // explicitly; this method no longer auto-creates one.
        taskStore.findActiveTaskForThread(thread.id()).ifPresent(t -> {
            Task next = new Task(
                    t.id(), t.threadId(), t.seq(),
                    mapStatus(thread.status()),
                    t.branchName(), t.worktreePath(), t.baseBranch(), t.workingDir(),
                    t.processPid(), t.logPath(),
                    t.prNumber(), t.prState(), t.ciState(),
                    t.taskType(), t.linkedPrNumber(), t.linkedIssueNumber(),
                    thread.costUsdMilli(), thread.tokensIn(), thread.tokensOut(),
                    t.agentSessionId(),
                    t.createdAt(),
                    thread.endedAt() != null ? thread.endedAt() : t.endedAt(),
                    thread.errorMessage() != null ? thread.errorMessage() : t.errorMessage(),
                    t.name(), t.roleSkill(), t.workModel());
            taskStore.saveTask(next);
        });
    }

    @Override
    public Optional<Thread> findThreadById(String id)
    {
        return threads.findById(id).map(this::merge);
    }

    @Override
    @Transactional
    public void deleteThread(String id)
    {
        if (!threads.existsById(id)) {
            return;
        }
        messages.deleteByThreadId(id);
        // FK cascade drops the tasks rows and task_files for this thread.
        threads.deleteById(id);
    }

    @Override
    public List<Thread> listTasksByStatus(ThreadStatus status, int limit)
    {
        return threads.findByStatusOrderByUpdatedAtMsDesc(status.name(), firstPage(limit))
                .stream()
                .map(this::merge)
                .toList();
    }

    @Override
    public List<Thread> listTasksByWorkspaceAndStatus(String workspaceId, ThreadStatus status, int limit)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return threads.findByStatusAndWorkspaceIdOrderByUpdatedAtMsDesc(
                        status.name(), workspaceId, firstPage(limit))
                .stream()
                .map(this::merge)
                .toList();
    }

    @Override
    public List<Thread> listTasksByIds(Collection<String> ids)
    {
        if (ids.isEmpty()) {
            return List.of();
        }
        return threads.findByIdInOrderByUpdatedAtMsDesc(ids).stream()
                .map(this::merge)
                .toList();
    }

    @Override
    public List<Thread> listThreadsUpdatedSince(Instant since)
    {
        return threads.findByUpdatedAtMsGreaterThanEqualOrderByUpdatedAtMsDesc(since.toEpochMilli())
                .stream()
                .map(this::merge)
                .toList();
    }

    @Override
    @Transactional
    public void appendMessage(ThreadMessage message)
    {
        ThreadMessageEntity entity = new ThreadMessageEntity();
        entity.setId(message.id());
        entity.setThreadId(message.threadId());
        entity.setTaskId(message.taskId());
        entity.setSeq(message.seq());
        entity.setRole(message.role());
        entity.setType(message.type());
        entity.setContentJson(message.contentJson());
        entity.setDurationMs(message.durationMs());
        entity.setTokensIn(message.tokensIn());
        entity.setTokensOut(message.tokensOut());
        entity.setCostUsdMilli(message.costUsdMilli());
        entity.setTsMs(message.ts().toEpochMilli());
        messages.save(entity);
    }

    @Override
    public List<ThreadMessage> listMessages(String threadId)
    {
        return messages.findByThreadIdOrderBySeqAsc(threadId).stream()
                .map(SqliteThreadStore::toMessage)
                .toList();
    }

    @Override
    public List<ThreadMessage> listRecentMessages(String threadId, int limit)
    {
        List<ThreadMessageEntity> tail = messages.findByThreadIdOrderBySeqDesc(
                threadId, PageRequest.of(0, Math.max(1, limit)));
        return reversedToMessages(tail);
    }

    @Override
    public List<ThreadMessage> listMessagesBefore(String threadId, long beforeSeq, int limit)
    {
        List<ThreadMessageEntity> older = messages.findByThreadIdAndSeqLessThanOrderBySeqDesc(
                threadId, beforeSeq, PageRequest.of(0, Math.max(1, limit)));
        return reversedToMessages(older);
    }

    @Override
    public long countUserMessages(String threadId)
    {
        return messages.countUserMessages(threadId);
    }

    @Override
    public List<ThreadMessage> listRecentUserMessages(String threadId, int limit)
    {
        List<ThreadMessageEntity> tail = messages.findByThreadIdAndRoleAndTypeOrderBySeqDesc(
                threadId, "user", "text", PageRequest.of(0, Math.max(1, limit)));
        return reversedToMessages(tail);
    }

    @Override
    public List<ThreadMessage> listUserMessagesBefore(String threadId, long beforeSeq, int limit)
    {
        List<ThreadMessageEntity> older = messages.findByThreadIdAndRoleAndTypeAndSeqLessThanOrderBySeqDesc(
                threadId, "user", "text", beforeSeq, PageRequest.of(0, Math.max(1, limit)));
        return reversedToMessages(older);
    }

    @Override
    public Optional<Long> maxMessageSeq(String threadId)
    {
        Long max = messages.maxSeq(threadId);
        return Optional.ofNullable(max);
    }

    @Override
    public long sumTokensBetween(String threadId, long firstSeq, long lastSeq)
    {
        if (firstSeq > lastSeq) {
            return 0;
        }
        return messages.sumTokensBetween(threadId, firstSeq, lastSeq);
    }

    @Override
    public List<ThreadMessage> listMessagesBetween(String threadId, long firstSeq, long lastSeq)
    {
        if (firstSeq > lastSeq) {
            return List.of();
        }
        return messages.findByThreadIdAndSeqBetween(threadId, firstSeq, lastSeq).stream()
                .map(SqliteThreadStore::toMessage)
                .toList();
    }

    private static List<ThreadMessage> reversedToMessages(List<ThreadMessageEntity> newestFirst)
    {
        List<ThreadMessage> out = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            out.add(toMessage(newestFirst.get(i)));
        }
        return List.copyOf(out);
    }

    @Override
    @Transactional
    public void recordFile(ThreadFile file)
    {
        // File rollups live on the task now. Resolve the active task and
        // forward; if there's no active task we drop the event — a
        // recordFile only fires while an agent is alive, which implies
        // an active task. The compile-time signature stays as ThreadFile
        // until the caller-side rename in a later commit.
        Optional<Task> active = taskStore.findActiveTaskForThread(file.threadId());
        if (active.isEmpty()) {
            return;
        }
        taskStore.recordFile(new TaskFile(
                active.get().id(),
                file.path(),
                file.operation(),
                file.count(),
                file.linesAdded(),
                file.linesRemoved(),
                file.lastTouchedAt()));
    }

    @Override
    public List<ThreadFile> listFiles(String threadId)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            return List.of();
        }
        return taskStore.listFiles(active.get().id()).stream()
                .map(tf -> new ThreadFile(
                        threadId, tf.path(), tf.operation(),
                        tf.count(), tf.linesAdded(), tf.linesRemoved(),
                        tf.lastTouchedAt()))
                .toList();
    }

    private Thread merge(ThreadEntity e)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(e.getId());
        return toThread(e, active.orElse(null));
    }

    private Thread toThread(ThreadEntity e, Task active)
    {
        // Bridge teardown complete: the work-unit fields are reached
        // exclusively via the activeTask projection now. Older
        // readers that wanted thread.workingDir / thread.branchName /
        // thread.worktreePath have been pivoted to
        // thread.activeTask().X.
        return new Thread(
                e.getId(),
                ThreadKind.valueOf(e.getKind()),
                e.getProvider(),
                e.getAgentSessionId(),
                e.getTitle(),
                ThreadStatus.valueOf(e.getStatus()),
                e.getModel(),
                e.getCostUsdMilli(),
                e.getTokensIn(),
                e.getTokensOut(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()),
                e.getEndedAtMs() == null ? null : Instant.ofEpochMilli(e.getEndedAtMs()),
                e.getErrorMessage(),
                ThreadFlow.fromDbValue(e.getFlow()),
                e.getWorkspaceId(),
                deserialiseWorkModel(e.getWorkModelJson()),
                active,
                e.getParentReviewPassId(),
                deserialiseQueue(e.getQueueJson()),
                e.getParallelSlots());
    }

    @Override
    @Transactional
    public void updateThreadQueue(String threadId, List<QueuedTask> queue)
    {
        threads.findById(threadId).ifPresent(entity -> {
            entity.setQueueJson(serialiseQueue(queue));
            threads.save(entity);
        });
    }

    /** Hand-rolled JSON for the queue so the wire shape (shared with the
     *  frontend's {@code GET /threads/{id}} read) is pinned here and
     *  Instant↔epoch-ms conversion is explicit — no reliance on a
     *  jsr310 module being registered on the ambient ObjectMapper. */
    private String serialiseQueue(List<QueuedTask> queue)
    {
        ArrayNode arr = objectMapper.createArrayNode();
        if (queue != null) {
            for (QueuedTask q : queue) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("position", q.position());
                node.put("title", q.title());
                node.put("branch_base", q.branchBase().wire());
                if (q.initialPrompt() != null) {
                    node.put("initial_prompt", q.initialPrompt());
                }
                node.put("status", q.status().name());
                if (q.materializedTaskId() != null) {
                    node.put("materialized_task_id", q.materializedTaskId());
                }
                node.put("created_at_ms",
                        q.createdAt() == null ? 0L : q.createdAt().toEpochMilli());
                arr.add(node);
            }
        }
        return arr.toString();
    }

    private List<QueuedTask> deserialiseQueue(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<QueuedTask> out = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                long createdMs = node.path("created_at_ms").asLong(0L);
                out.add(new QueuedTask(
                        node.path("position").asInt(),
                        node.path("title").asText(""),
                        BranchBase.fromWire(node.path("branch_base").asText(null)),
                        node.hasNonNull("initial_prompt") ? node.get("initial_prompt").asText() : null,
                        QueuedTaskStatus.fromWire(node.path("status").asText(null)),
                        node.hasNonNull("materialized_task_id")
                                ? node.get("materialized_task_id").asText() : null,
                        Instant.ofEpochMilli(createdMs)));
            }
            return List.copyOf(out);
        }
        catch (JsonProcessingException e) {
            // A malformed queue row shouldn't break the whole thread
            // load — treat as an empty queue.
            return List.of();
        }
    }

    private String serialiseWorkModel(WorkModel m)
    {
        if (m == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(m);
        }
        catch (JsonProcessingException e) {
            // The record is plain-object-mapper-friendly, so a failure
            // here means a programming error — surface it loudly.
            throw new IllegalStateException("WorkModel JSON serialise failed", e);
        }
    }

    private WorkModel deserialiseWorkModel(String json)
    {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WorkModel.class);
        }
        catch (JsonProcessingException e) {
            // Bad row → treat as "no override". The resolver falls back
            // to the workspace pick. Don't break the whole thread load
            // because of a stale JSON shape.
            return null;
        }
    }

    private static ThreadMessage toMessage(ThreadMessageEntity e)
    {
        return new ThreadMessage(
                e.getId(),
                e.getThreadId(),
                e.getTaskId(),
                e.getSeq(),
                e.getRole(),
                e.getType(),
                e.getContentJson(),
                e.getDurationMs(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getTsMs()));
    }

    private static TaskStatus mapStatus(ThreadStatus status)
    {
        return TaskStatus.valueOf(status.name());
    }
}
