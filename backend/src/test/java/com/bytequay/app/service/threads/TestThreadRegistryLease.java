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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorktreeLeaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for ThreadRegistry's worktree-lease lifecycle.
 * Per the workspace/thread/task design, the lease is the lock on
 * the worktree — taken when a session attaches, released when the
 * session is evicted. Per-turn acquire/release would let auto-fix
 * barge in during the idle window between prompts, which is the
 * exact failure mode this test class guards against.
 */
class TestThreadRegistryLease
{
    private static final String WORKTREE = "/tmp/repo/.worktrees/task-1";

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final InMemoryLeaseStore leaseStore = new InMemoryLeaseStore();
    private final WorktreeLeaseService leaseService = new WorktreeLeaseService(leaseStore);

    @Test
    void getOrCreateAcquiresWorktreeLeaseForActiveTask()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Task active = task("task-1", "thread-1", WORKTREE);
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(active));
        ThreadRegistry registry = newRegistry();

        registry.getOrCreate(thread("thread-1"));

        assertThat(leaseService.isHeld(WORKTREE)).isTrue();
    }

    @Test
    void evictReleasesTheWorktreeLease()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(taskStore.findActiveTaskForThread("thread-1"))
                .thenReturn(Optional.of(task("task-1", "thread-1", WORKTREE)));
        ThreadRegistry registry = newRegistry();
        registry.getOrCreate(thread("thread-1"));

        registry.evict("thread-1");

        assertThat(leaseService.isHeld(WORKTREE)).isFalse();
    }

    @Test
    void getOrCreateRefusesWhenLeaseHeldByLiveHolder()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(taskStore.findActiveTaskForThread("thread-1"))
                .thenReturn(Optional.of(task("task-1", "thread-1", WORKTREE)));
        // Seed a lease held by *this* JVM's pid so the liveness check
        // sees a live holder — that's the "another agent already
        // attached" case, which must surface a 409.
        leaseStore.save(new WorktreeLease(
                WORKTREE, "task-99", ThreadKind.CLI_AGENT,
                (int) ProcessHandle.current().pid(),
                Instant.now(), /* expiresAt */ null));
        ThreadRegistry registry = newRegistry();

        assertThatThrownBy(() -> registry.getOrCreate(thread("thread-1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("leased by another live session");
        // The pre-existing row stays put — refusing the attachment
        // must not nuke the legitimate holder's lease.
        assertThat(leaseService.find(WORKTREE).map(WorktreeLease::taskId))
                .contains("task-99");
    }

    @Test
    void getOrCreateReclaimsLeaseWhenPriorHolderProcessIsGone()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(taskStore.findActiveTaskForThread("thread-1"))
                .thenReturn(Optional.of(task("task-1", "thread-1", WORKTREE)));
        // Seed a lease held by a pid that doesn't exist. The
        // registry should reclaim it (the prior JVM died and never
        // got to release) rather than refusing the attach.
        leaseStore.save(new WorktreeLease(
                WORKTREE, "task-99", ThreadKind.CLI_AGENT,
                /* holderPid */ deadPid(),
                Instant.now().minusSeconds(3600), /* expiresAt */ null));
        ThreadRegistry registry = newRegistry();

        registry.getOrCreate(thread("thread-1"));

        assertThat(leaseService.find(WORKTREE).map(WorktreeLease::taskId))
                .contains("task-1");
    }

    @Test
    void evictIsANoOpWhenThereWasNoSession()
    {
        ThreadRegistry registry = newRegistry();

        registry.evict("thread-unknown");

        // No exception, no stray store interactions.
        verify(taskStore, never()).findActiveTaskForThread("thread-unknown");
    }

    @Test
    void getOrCreateRefusesABrainstormThreadAndLeavesLeaseStoreUntouched()
    {
        // 0-Task brainstorm thread — no worktree to protect and no
        // agent to spawn. The agent ctor refuses to build, the lease
        // never gets a chance to land, and the lease store stays
        // empty.
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.empty());
        ThreadRegistry registry = newRegistry();

        assertThatThrownBy(() -> registry.getOrCreate(thread("thread-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no task");

        assertThat(leaseStore.rows).isEmpty();
    }

    @Test
    void getOrCreateIsIdempotentForTheSameThread()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Task active = task("task-1", "thread-1", WORKTREE);
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(active));
        ThreadRegistry registry = newRegistry();

        registry.getOrCreate(thread("thread-1"));
        registry.getOrCreate(thread("thread-1"));

        // Two getOrCreate calls — the first looks up the active task
        // twice (once for the lease acquire, once inside the agent
        // ctor), the second returns the cached session without
        // touching the task store again.
        verify(taskStore, times(2)).findActiveTaskForThread("thread-1");
        assertThat(leaseService.isHeld(WORKTREE)).isTrue();
    }

    private ThreadRegistry newRegistry()
    {
        ObjectMapper mapper = new ObjectMapper();
        return new ThreadRegistry(
                threadStore,
                taskStore,
                new StreamJsonParser(mapper),
                mapper,
                mock(McpPermissionGate.class),
                sameThreadExecutor(),
                mock(CheckpointTrigger.class),
                () -> "",
                leaseService);
    }

    private static Thread thread(String id)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Registry lease test", ThreadStatus.IDLE,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null);
    }

    private static Task task(String id, String threadId, String worktreePath)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                id, threadId, /* seq */ 1L, TaskStatus.RUNNING,
                /* branchName */ "auto/" + id,
                worktreePath,
                /* baseBranch */ "main",
                /* workingDir */ "/tmp/repo",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, null, null, null);
    }

    private static int deadPid()
    {
        // PID 1 is init/launchd; spawning a short-lived child and
        // reading its pid after wait would be more correct but flaky
        // in CI. Picking a clearly-implausible high number is safer:
        // it's outside the OS's per-pid cap on macOS / Linux for any
        // realistic test box, so ProcessHandle.of returns empty.
        return 2_000_000_000;
    }

    private static ExecutorService sameThreadExecutor()
    {
        return new AbstractExecutorService()
        {
            private volatile boolean shutdown;

            @Override public void shutdown() { shutdown = true; }

            @Override
            public List<Runnable> shutdownNow()
            {
                shutdown = true;
                return List.of();
            }

            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    /** In-memory lease store with the same atomicity guarantees the
     *  SQLite impl provides via its PK on worktree_path — the second
     *  insert against the same path fails so tryAcquire actually
     *  serialises. */
    private static final class InMemoryLeaseStore
            implements WorktreeLeaseStore
    {
        private final Map<String, WorktreeLease> rows = new HashMap<>();

        @Override
        public void save(WorktreeLease lease)
        {
            if (rows.containsKey(lease.worktreePath())) {
                throw new DataIntegrityViolationException(
                        "duplicate worktree_path: " + lease.worktreePath());
            }
            rows.put(lease.worktreePath(), lease);
        }

        @Override
        public Optional<WorktreeLease> findByWorktreePath(String worktreePath)
        {
            return Optional.ofNullable(rows.get(worktreePath));
        }

        @Override
        public List<WorktreeLease> listAll()
        {
            return List.copyOf(rows.values());
        }

        @Override
        public List<WorktreeLease> listForTask(String taskId)
        {
            return rows.values().stream()
                    .filter(l -> taskId.equals(l.taskId()))
                    .toList();
        }

        @Override
        public void releaseByWorktreePath(String worktreePath)
        {
            rows.remove(worktreePath);
        }
    }
}
