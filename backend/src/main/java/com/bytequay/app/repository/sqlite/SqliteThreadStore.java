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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageMessageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final StageMessageStore stageMessages;
    private final TaskStore taskStore;
    private final ObjectMapper objectMapper;

    SqliteThreadStore(
            ThreadJpaRepository threads,
            ThreadMessageJpaRepository messages,
            StageMessageStore stageMessages,
            TaskStore taskStore,
            ObjectMapper objectMapper)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.messages = requireNonNull(messages, "messages is null");
        this.stageMessages = requireNonNull(stageMessages, "stageMessages is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    // ── Stage transcripts — delegate to the decoupled stage_messages store ──

    @Override
    public void appendStageMessage(ThreadMessage message)
    {
        stageMessages.appendMessage(message);
    }

    @Override
    public Optional<Long> maxStageMessageSeq(String stageId)
    {
        return stageMessages.maxMessageSeq(stageId);
    }

    @Override
    public List<ThreadMessage> listStageMessages(String stageId)
    {
        return stageMessages.listMessages(stageId);
    }

    @Override
    public List<ThreadMessage> listStageMessagesByTask(String taskId)
    {
        return stageMessages.listMessagesByTask(taskId);
    }

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
        entity.setEndedAtMs(Timestamps.epochMilli(thread.endedAt()));
        entity.setErrorMessage(thread.errorMessage());
        // Honour the caller's workspace assignment on create. Existing
        // rows keep their stored workspaceId; later saveThread calls
        // only update mutable fields.
        if (existing.isEmpty()) {
            String desired = thread.workspaceId();
            if (desired == null || desired.isBlank()) {
                throw new IllegalArgumentException("workspaceId is required for new thread " + thread.id());
            }
            entity.setWorkspaceId(desired);
        }
        // Flow is set-once: write it on INSERT, refuse to silently
        // flip it on UPDATE. The build vs review discriminator is a
        // structural property of the thread; if a caller wants to
        // change it, that's a different thread.
        if (existing.isEmpty()) {
            ThreadFlow flow = thread.flow() == null ? ThreadFlow.BUILD : thread.flow();
            entity.setFlow(flow.dbValue());
            // parallel_slots is structural (v1 invariant 1) — write it on
            // INSERT only.
            entity.setParallelSlots(thread.parallelSlots() < 1 ? 1 : thread.parallelSlots());
            // parent_task_id is structural too — a brain thread's 1:1 link
            // to its task is set at creation and never changes.
            entity.setParentTaskId(thread.parentTaskId());
        }
        else if (thread.flow() != null
                && !thread.flow().dbValue().equals(entity.getFlow())) {
            throw new IllegalStateException(
                    "thread.flow is set-once; cannot flip thread " + thread.id()
                            + " from " + entity.getFlow() + " to " + thread.flow().dbValue());
        }
        entity.setWorkModelJson(WorkModelJson.serialise(objectMapper, thread.workModel()));
        entity.setParentReviewPassId(thread.parentReviewPassId());
        threads.save(entity);

        // Mirror Thread-level lifecycle state (status, endedAt,
        // errorMessage) onto the active task so a reader of the task row
        // sees a consistent picture. Cost / tokens are deliberately NOT
        // mirrored: the thread's counters are LIFETIME-cumulative across
        // every task in the chain, so copying them onto a task makes a
        // freshly-cut task inherit the whole thread's spend (a brand-new
        // task showed 26M tokens this way). Each task owns its own usage —
        // the agent accumulates it task-scoped and persists it via
        // TaskStore — so here we preserve the task's existing values.
        // branchName / worktreePath / workingDir / linked PR/issue /
        // taskType also live on the task and aren't overridden via Thread.
        // A thread can run several active tasks; mirror lifecycle onto each
        // so a reader of any of them sees a consistent picture.
        taskStore.activeTasksForThread(thread.id()).forEach(task -> {
            Task next = new Task(
                    task.id(),
                    task.threadId(),
                    task.seq(),
                    mapStatus(thread.status()),
                    task.branchName(),
                    task.worktreePath(),
                    task.baseBranch(),
                    task.workingDir(),
                    task.processPid(),
                    task.logPath(),
                    task.prNumber(),
                    task.prState(),
                    task.ciState(),
                    task.taskType(),
                    task.linkedPrNumber(),
                    task.linkedIssueNumber(),
                    task.costUsdMilli(),
                    task.tokensIn(),
                    task.tokensOut(),
                    task.agentSessionId(),
                    task.createdAt(),
                    thread.endedAt() != null ? thread.endedAt() : task.endedAt(),
                    thread.errorMessage() != null ? thread.errorMessage() : task.errorMessage(),
                    task.name(),
                    task.roleSkill(),
                    task.workModel());
            taskStore.saveTask(next);
        });
    }

    @Override
    public Optional<Thread> findThreadById(String id)
    {
        return threads.findById(id).map(this::merge);
    }

    @Override
    public Optional<PlanningSnapshot> findPlanningSnapshot(String threadId)
    {
        return threads.findById(threadId)
                .filter(entity -> entity.getPlanningRepoRoot() != null
                        && !entity.getPlanningRepoRoot().isBlank()
                        && entity.getPlanningBaseSha() != null
                        && !entity.getPlanningBaseSha().isBlank())
                .map(entity -> new PlanningSnapshot(
                        entity.getPlanningRepoRoot(), entity.getPlanningBaseSha()));
    }

    @Override
    @Transactional
    public void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        ThreadEntity entity = threads.findById(threadId)
                .orElseThrow(() -> new IllegalArgumentException("no thread: " + threadId));
        entity.setPlanningRepoRoot(requireNonNull(snapshot.repoRoot(), "repoRoot is null"));
        entity.setPlanningBaseSha(requireNonNull(snapshot.baseSha(), "baseSha is null"));
        threads.save(entity);
    }

    @Override
    @Transactional
    public boolean clearPlanningSnapshot(String threadId, String expectedBaseSha)
    {
        Optional<ThreadEntity> found = threads.findById(threadId);
        if (found.isEmpty() || expectedBaseSha == null
                || !expectedBaseSha.equals(found.get().getPlanningBaseSha())) {
            return false;
        }
        ThreadEntity entity = found.get();
        entity.setPlanningRepoRoot(null);
        entity.setPlanningBaseSha(null);
        threads.save(entity);
        return true;
    }

    @Override
    public Optional<Thread> findBrainThreadByTask(String taskId)
    {
        return threads.findFirstByKindAndParentTaskId(ThreadKind.BRAIN_AGENT.name(), taskId)
                .map(this::merge);
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
    public List<Thread> listThreadsByWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return threads.findByWorkspaceId(workspaceId).stream()
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
    public List<AiSpendRow> aggregateAiSpend(Instant start, Instant end)
    {
        return messages.aggregateAiSpend(start.toEpochMilli(), end.toEpochMilli()).stream()
                .map(r -> new AiSpendRow(
                        (String) r[0], (String) r[1], (String) r[2],
                        ((Number) r[3]).longValue(), ((Number) r[4]).longValue()))
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
        entity.setStageId(message.stageId());
        entity.setScope(message.scope() == null ? null : message.scope().name());
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
        // File rollups live on the task now. ThreadFile carries no task id,
        // so attribute to the thread's latest active task (a recordFile only
        // fires while an agent is alive, which implies one); drop the event
        // when no active task exists.
        // ponytail: thread-level rollups can't disambiguate among concurrent
        // tasks — they attribute to the newest. Stamp a task id on the event
        // if per-task accuracy ever matters.
        Optional<Task> active = taskStore.activeTasksForThread(file.threadId()).stream().findFirst();
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
        Optional<Task> active = taskStore.activeTasksForThread(threadId).stream().findFirst();
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
                Timestamps.instant(e.getEndedAtMs()),
                e.getErrorMessage(),
                ThreadFlow.fromDbValue(e.getFlow()),
                e.getWorkspaceId(),
                WorkModelJson.deserialise(objectMapper, e.getWorkModelJson()),
                e.getParentReviewPassId(),
                e.getParallelSlots(),
                e.getParentTaskId());
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
                Instant.ofEpochMilli(e.getTsMs()),
                e.getStageId(),
                e.getScope() == null
                        ? ThreadScope.of(e.getTaskId(), e.getStageId())
                        : ThreadScope.valueOf(e.getScope()));
    }

    private static TaskStatus mapStatus(ThreadStatus status)
    {
        return TaskStatus.valueOf(status.name());
    }
}
