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
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    SqliteThreadStore(
            ThreadJpaRepository threads,
            ThreadMessageJpaRepository messages,
            TaskStore taskStore)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.messages = requireNonNull(messages, "messages is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
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
        if (entity.getWorkspaceId() == null) {
            entity.setWorkspaceId(DEFAULT_WORKSPACE_ID);
        }
        // Flow is set-once: write it on INSERT, refuse to silently
        // flip it on UPDATE. The build vs review discriminator is a
        // structural property of the thread; if a caller wants to
        // change it, that's a different thread.
        if (existing.isEmpty()) {
            ThreadFlow flow = thread.flow() == null ? ThreadFlow.BUILD : thread.flow();
            entity.setFlow(flow.dbValue());
        }
        else if (thread.flow() != null
                && !thread.flow().dbValue().equals(entity.getFlow())) {
            throw new IllegalStateException(
                    "thread.flow is set-once; cannot flip thread " + thread.id()
                            + " from " + entity.getFlow() + " to " + thread.flow().dbValue());
        }
        threads.save(entity);

        // Flow execution fields to the active task. We treat the active
        // task as the home for branchName / worktreePath / processPid /
        // logPath / linked PR/issue / per-task spend; the existing
        // callers still pass these through the Thread record.
        if (hasExecutionState(thread)) {
            Optional<Task> active = taskStore.findActiveTaskForThread(thread.id());
            Task next;
            if (active.isPresent()) {
                Task t = active.get();
                next = new Task(
                        t.id(),
                        t.threadId(),
                        t.seq(),
                        mapStatus(thread.status()),
                        coalesce(thread.branchName(), t.branchName()),
                        coalesce(thread.worktreePath(), t.worktreePath()),
                        t.baseBranch(),
                        coalesce(thread.workingDir(), t.workingDir()),
                        // processPid + logPath are owned by the task
                        // now (V72). Saving via Thread doesn't carry
                        // them anymore; preserve whatever the task
                        // row already holds.
                        t.processPid(),
                        t.logPath(),
                        t.prNumber(),
                        t.prState(),
                        t.ciState(),
                        coalesce(thread.taskType(), t.taskType()),
                        // linkedPrNumber / linkedIssueNumber live on
                        // the Task only now; Thread no longer carries
                        // them so the split path preserves what the
                        // task already holds.
                        t.linkedPrNumber(),
                        t.linkedIssueNumber(),
                        thread.costUsdMilli(),
                        thread.tokensIn(),
                        thread.tokensOut(),
                        t.firstMsgSeq(),
                        t.lastMsgSeq(),
                        t.createdAt(),
                        thread.endedAt() != null ? thread.endedAt() : t.endedAt(),
                        thread.errorMessage() != null ? thread.errorMessage() : t.errorMessage());
            }
            else {
                // First save for this thread — materialise a seq=1 task.
                next = new Task(
                        UUID.randomUUID().toString(),
                        thread.id(),
                        1L,
                        mapStatus(thread.status()),
                        thread.branchName(),
                        thread.worktreePath(),
                        "main",
                        thread.workingDir(),
                        /* processPid */ null,
                        /* logPath */ null,
                        /* prNumber */ null, /* prState */ null, /* ciState */ null,
                        thread.taskType() != null ? thread.taskType() : "DEVELOP",
                        /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                        thread.costUsdMilli(),
                        thread.tokensIn(),
                        thread.tokensOut(),
                        /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                        thread.createdAt(),
                        thread.endedAt(),
                        thread.errorMessage());
            }
            taskStore.saveTask(next);
        }
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
    @Transactional
    public void appendMessage(ThreadMessage message)
    {
        ThreadMessageEntity entity = new ThreadMessageEntity();
        entity.setId(message.id());
        entity.setTaskId(message.threadId());
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

    private static Thread toThread(ThreadEntity e, Task active)
    {
        // Bridge projection: V72 moved the work-unit columns onto
        // tasks. The active Task rides along on the Thread payload
        // so new readers can use thread.activeTask().X — flattened
        // bridge scalars are kept for older readers and shrink as
        // the teardown progresses.
        String workingDir = active != null ? active.workingDir() : null;
        String branchName = active != null ? active.branchName() : null;
        String worktreePath = active != null ? active.worktreePath() : null;
        String taskType = active != null ? active.taskType() : null;
        return new Thread(
                e.getId(),
                ThreadKind.valueOf(e.getKind()),
                e.getProvider(),
                e.getAgentSessionId(),
                e.getTitle(),
                ThreadStatus.valueOf(e.getStatus()),
                workingDir,
                branchName,
                e.getModel(),
                e.getCostUsdMilli(),
                e.getTokensIn(),
                e.getTokensOut(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()),
                e.getEndedAtMs() == null ? null : Instant.ofEpochMilli(e.getEndedAtMs()),
                e.getErrorMessage(),
                taskType,
                worktreePath,
                ThreadFlow.fromDbValue(e.getFlow()),
                active);
    }

    private static ThreadMessage toMessage(ThreadMessageEntity e)
    {
        return new ThreadMessage(
                e.getId(),
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

    private static boolean hasExecutionState(Thread thread)
    {
        return thread.worktreePath() != null
                || thread.branchName() != null
                || thread.workingDir() != null;
    }

    private static TaskStatus mapStatus(ThreadStatus status)
    {
        return TaskStatus.valueOf(status.name());
    }

    @SafeVarargs
    private static <T> T coalesce(T... values)
    {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
