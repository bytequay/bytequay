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

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of {@link SqliteThreadStore} against the real
 * Flyway-migrated SQLite schema. Catches schema/entity drift, JPA
 * mapping bugs, and converter issues that the smoke test wouldn't.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteThreadStore
{
    @Autowired
    private ThreadStore store;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private StageStore stageStore;

    @Test
    void stageScopedMessagesRouteToTheStageStoreNotTheThreadLog()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(thread);
        Instant ts = Instant.parse("2026-05-15T12:00:00Z");
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, ts, null, null, null, null, null));
        String stageId = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null).id().toString();

        store.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), thread.id(), taskId, 0L,
                "assistant", "text", "stage row", null, null, null, 0L, ts,
                stageId, ThreadScope.STAGE));
        // A trunk row for contrast — stays in the per-thread log.
        store.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), thread.id(), null, 0L,
                "user", "text", "trunk row", null, null, null, 0L, ts));

        // The stage row landed in the decoupled stage_messages store …
        assertThat(store.listStageMessages(stageId)).extracting(ThreadMessage::contentJson)
                .containsExactly("stage row");
        assertThat(store.maxStageMessageSeq(stageId)).hasValue(0L);
        assertThat(store.listStageMessagesByTask(taskId)).hasSize(1);
        // … and NOT in the per-thread log — so seq 0 is free for both with no
        // collision on the old thread-global (thread_id, seq) key.
        assertThat(store.listMessages(thread.id())).extracting(ThreadMessage::contentJson)
                .containsExactly("trunk row");
    }

    @Test
    void roundtripsACliAgentTask()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(thread);

        Optional<Thread> loaded = store.findThreadById(thread.id());
        assertThat(loaded).isPresent();
        Thread got = loaded.get();
        assertThat(got.id()).isEqualTo(thread.id());
        assertThat(got.kind()).isEqualTo(ThreadKind.CLI_AGENT);
        assertThat(got.status()).isEqualTo(ThreadStatus.RUNNING);
    }

    @Test
    void roundtripsALogicLoopTaskWithNullCliFields()
    {
        Thread thread = newTask(ThreadKind.LOGIC_LOOP, ThreadStatus.PENDING);
        store.saveThread(thread);

        Thread got = store.findThreadById(thread.id()).orElseThrow();
        assertThat(got.kind()).isEqualTo(ThreadKind.LOGIC_LOOP);
        assertThat(got.agentSessionId()).isNull();
    }

    @Test
    void planningSnapshotPersistsAndClearsByExpectedSha()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE);
        store.saveThread(thread);
        ThreadStore.PlanningSnapshot snapshot =
                new ThreadStore.PlanningSnapshot("/tmp/repo", "abc123");

        store.setPlanningSnapshot(thread.id(), snapshot);
        assertThat(store.findPlanningSnapshot(thread.id())).contains(snapshot);
        assertThat(store.clearPlanningSnapshot(thread.id(), "different-sha")).isFalse();
        assertThat(store.findPlanningSnapshot(thread.id())).contains(snapshot);
        assertThat(store.clearPlanningSnapshot(thread.id(), "abc123")).isTrue();
        assertThat(store.findPlanningSnapshot(thread.id())).isEmpty();
    }

    @Test
    void saveTaskUpdatesInPlaceWhenIdMatches()
    {
        Thread initial = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(initial);

        Thread updated = new Thread(
                initial.id(), initial.kind(), initial.provider(), "agent-session-abc",
                initial.title(), ThreadStatus.AWAITING,
                initial.model(),
                /* costUsdMilli */ 12_345L, /* tokensIn */ 1_000L, /* tokensOut */ 2_000L,
                initial.createdAt(), Instant.parse("2026-05-15T13:00:00Z"),
                /* endedAt */ null, /* errorMessage */ null,
                initial.flow(),
                "ws-default",
                initial.workModel());
        store.saveThread(updated);

        Thread got = store.findThreadById(initial.id()).orElseThrow();
        assertThat(got.status()).isEqualTo(ThreadStatus.AWAITING);
        assertThat(got.agentSessionId()).isEqualTo("agent-session-abc");
        assertThat(got.costUsdMilli()).isEqualTo(12_345L);
        assertThat(got.tokensIn()).isEqualTo(1_000L);
        assertThat(got.updatedAt()).isEqualTo(Instant.parse("2026-05-15T13:00:00Z"));
    }

    @Test
    void flowRoundTripsAndCannotBeFlippedOnUpdate()
    {
        Thread original = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(original);
        assertThat(store.findThreadById(original.id()).orElseThrow().flow())
                .isEqualTo(ThreadFlow.BUILD);

        // Attempting to flip flow to REVIEW on an existing row must
        // throw — this is the invariant V74 set up but couldn't enforce
        // until the domain field landed.
        Thread flipped = new Thread(
                original.id(), original.kind(), original.provider(), original.agentSessionId(),
                original.title(), original.status(),
                original.model(),
                original.costUsdMilli(), original.tokensIn(), original.tokensOut(),
                original.createdAt(), original.updatedAt(),
                original.endedAt(), original.errorMessage(),
                ThreadFlow.REVIEW,
                "ws-default",
                original.workModel());
        assertThatThrownBy(() -> store.saveThread(flipped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("set-once");

        // Re-saving with the same flow value is fine — the no-flip
        // rule only fires when the value actually changes.
        store.saveThread(original);
        assertThat(store.findThreadById(original.id()).orElseThrow().flow())
                .isEqualTo(ThreadFlow.BUILD);
    }

    @Test
    void listTasksByStatusOrdersNewestFirstAndCapsToLimit()
    {
        Instant base = Instant.parse("2026-05-15T12:00:00Z");
        for (int i = 0; i < 5; i++) {
            Thread t = withTimestamps(
                    newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE),
                    base, base.plusSeconds(i));
            store.saveThread(t);
        }

        List<Thread> page = store.listTasksByStatus(ThreadStatus.IDLE, 3);
        assertThat(page).hasSize(3);
        // Newest updated_at_ms first.
        assertThat(page.get(0).updatedAt()).isAfterOrEqualTo(page.get(1).updatedAt());
        assertThat(page.get(1).updatedAt()).isAfterOrEqualTo(page.get(2).updatedAt());
        // Status filter actually filtered.
        assertThat(page).allSatisfy(t -> assertThat(t.status()).isEqualTo(ThreadStatus.IDLE));
    }

    @Test
    void listTasksByStatusRejectsNonPositiveLimit()
    {
        assertThatThrownBy(() -> store.listTasksByStatus(ThreadStatus.IDLE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void appendsAndListsMessagesInSeqOrder()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(thread);

        store.appendMessage(message(thread.id(), 0, "user", "text", "{\"text\":\"fix the bug\"}"));
        store.appendMessage(message(thread.id(), 1, "assistant", "thinking", "{\"summary\":\"reading the file\"}"));
        store.appendMessage(message(thread.id(), 2, "tool", "tool_call",
                "{\"toolName\":\"Read\",\"input\":{\"path\":\"src/main.ts\"}}"));

        List<ThreadMessage> all = store.listMessages(thread.id());
        assertThat(all).extracting(ThreadMessage::seq).containsExactly(0L, 1L, 2L);
        assertThat(all).extracting(ThreadMessage::type)
                .containsExactly("text", "thinking", "tool_call");
    }

    @Test
    void roundtripsATaskWorkModelOverride()
    {
        // The task work-model override is the most-specific scope on
        // the cascade; same JSON shape as the thread + workspace
        // columns, exercised here on the task row.
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE);
        store.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        Instant created = Instant.parse("2026-05-15T12:00:00Z");
        Task pinned = new Task(
                taskId, thread.id(), 1L, TaskStatus.IDLE,
                "main", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null, created, null, null, null, null,
                new WorkModel(WorkModelKind.CLI, "claude-code", "claude-opus-4-7", null));
        taskStore.saveTask(pinned);

        Task got = taskStore.findTaskById(taskId).orElseThrow();
        assertThat(got.workModel()).isNotNull();
        assertThat(got.workModel().kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(got.workModel().agentOrProvider()).isEqualTo("claude-code");
        assertThat(got.workModel().model()).isEqualTo("claude-opus-4-7");
        assertThat(got.workModel().account()).isNull();

        // Clearing the override on a follow-up save round-trips as
        // null so the resolver falls back to the thread pick.
        Task cleared = new Task(
                got.id(), got.threadId(), got.seq(), got.status(),
                got.branchName(), got.worktreePath(), got.baseBranch(), got.workingDir(),
                got.processPid(), got.logPath(),
                got.prNumber(), got.prState(), got.ciState(),
                got.taskType(), got.linkedPrNumber(), got.linkedIssueNumber(),
                got.costUsdMilli(), got.tokensIn(), got.tokensOut(),
                got.agentSessionId(),
                got.createdAt(), got.endedAt(), got.errorMessage(),
                got.name(), got.roleSkill(), /* workModel */ null);
        taskStore.saveTask(cleared);
        assertThat(taskStore.findTaskById(taskId).orElseThrow().workModel()).isNull();
    }

    @Test
    void taskOriginIsPersistedOnce()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE);
        store.saveThread(thread);
        Instant created = Instant.parse("2026-07-20T00:00:00Z");
        Task monitored = new Task(
                UUID.randomUUID().toString(), thread.id(), 1L, TaskStatus.PENDING,
                "issue-12", null, "main", "/tmp", null, null,
                null, null, null, "BYTEQUAY_ISSUE_TRIAGE", null, 12,
                0L, 0L, 0L, null, created, null, null,
                "Triage issue", null, null, Task.ORIGIN_ISSUE_MONITOR);
        taskStore.saveTask(monitored);

        Task loaded = taskStore.findTaskById(monitored.id()).orElseThrow();
        assertThat(loaded.origin()).isEqualTo(Task.ORIGIN_ISSUE_MONITOR);

        Task attemptedRewrite = new Task(
                loaded.id(), loaded.threadId(), loaded.seq(), TaskStatus.IDLE,
                loaded.branchName(), loaded.worktreePath(), loaded.baseBranch(), loaded.workingDir(),
                loaded.processPid(), loaded.logPath(), loaded.prNumber(), loaded.prState(),
                loaded.ciState(), loaded.taskType(), loaded.linkedPrNumber(), loaded.linkedIssueNumber(),
                loaded.costUsdMilli(), loaded.tokensIn(), loaded.tokensOut(), loaded.agentSessionId(),
                loaded.createdAt(), loaded.endedAt(), loaded.errorMessage(), loaded.name(),
                loaded.roleSkill(), loaded.workModel(), Task.ORIGIN_USER);
        assertThatThrownBy(() -> taskStore.saveTask(attemptedRewrite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("task origin is immutable: issue-monitor -> user");

        assertThat(taskStore.findTaskById(monitored.id()).orElseThrow().origin())
                .isEqualTo(Task.ORIGIN_ISSUE_MONITOR);
    }

    @Test
    void roundtripsAThreadWorkModelOverride()
    {
        // The same JSON shape the workspace round-trip exercises;
        // the column is per-scope so the same serialiser is reused.
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE);
        Thread withPin = new Thread(
                thread.id(), thread.kind(), thread.provider(), thread.agentSessionId(),
                thread.title(), thread.status(), thread.model(),
                thread.costUsdMilli(), thread.tokensIn(), thread.tokensOut(),
                thread.createdAt(), thread.updatedAt(), thread.endedAt(), thread.errorMessage(),
                thread.flow(), thread.workspaceId(),
                new WorkModel(WorkModelKind.API, "anthropic", null, "team"));
        store.saveThread(withPin);

        Thread got = store.findThreadById(thread.id()).orElseThrow();
        assertThat(got.workModel()).isNotNull();
        assertThat(got.workModel().kind()).isEqualTo(WorkModelKind.API);
        assertThat(got.workModel().agentOrProvider()).isEqualTo("anthropic");
        assertThat(got.workModel().account()).isEqualTo("team");

        // Clearing the override round-trips as null — the resolver
        // falls back to the workspace pick in that case.
        Thread cleared = new Thread(
                got.id(), got.kind(), got.provider(), got.agentSessionId(),
                got.title(), got.status(), got.model(),
                got.costUsdMilli(), got.tokensIn(), got.tokensOut(),
                got.createdAt(), got.updatedAt(), got.endedAt(), got.errorMessage(),
                got.flow(), got.workspaceId(), /* workModel */ null);
        store.saveThread(cleared);
        assertThat(store.findThreadById(thread.id()).orElseThrow().workModel()).isNull();
    }

    @Test
    void recordFileUpsertsByCompositeKey()
    {
        Thread thread = newTask(ThreadKind.LOGIC_LOOP, ThreadStatus.RUNNING);
        store.saveThread(thread);
        // recordFile / listFiles delegate to the active task (V72
        // moved the file ledger off threads). Seed a task explicitly
        // since saveThread no longer auto-materialises one.
        Instant taskCreated = Instant.parse("2026-05-15T12:00:00Z");
        taskStore.saveTask(new Task(
                UUID.randomUUID().toString(), thread.id(), 1L, TaskStatus.RUNNING,
                "main", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null, taskCreated, null, null, null, null, null));

        Instant first = Instant.parse("2026-05-15T12:00:00Z");
        Instant second = Instant.parse("2026-05-15T12:05:00Z");
        store.recordFile(new ThreadFile(thread.id(), "src/foo.ts", "read", 1, 0, 0, first));
        // Same (threadId, path) — should overwrite, not duplicate.
        store.recordFile(new ThreadFile(thread.id(), "src/foo.ts", "edit", 3, 12, 4, second));
        // Different path — separate row.
        store.recordFile(new ThreadFile(thread.id(), "src/bar.ts", "write", 1, 50, 0, first));

        List<ThreadFile> got = store.listFiles(thread.id());
        assertThat(got).hasSize(2);
        ThreadFile foo = got.stream().filter(f -> f.path().equals("src/foo.ts")).findFirst().orElseThrow();
        assertThat(foo.operation()).isEqualTo("edit");
        assertThat(foo.count()).isEqualTo(3);
        assertThat(foo.linesAdded()).isEqualTo(12);
        assertThat(foo.lastTouchedAt()).isEqualTo(second);
    }

    @Test
    void findActiveTaskIgnoresTasksWhoseStatusLagsACompletedPhase()
    {
        Thread thread = newTask(ThreadKind.LOGIC_LOOP, ThreadStatus.RUNNING);
        store.saveThread(thread);
        Instant now = Instant.parse("2026-05-15T12:00:00Z");

        // A genuinely active task — IDLE status, default (non-terminal) phase.
        String activeId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                activeId, thread.id(), 1L, TaskStatus.IDLE, "main", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        assertThat(taskStore.activeTasksForThread(thread.id()).stream().findFirst().map(Task::id))
                .hasValue(activeId);

        // A done task whose runtime status lags: IDLE but phase COMPLETED,
        // and a higher seq so it would win on status alone.
        String laggingId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                laggingId, thread.id(), 2L, TaskStatus.IDLE, "main", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        taskStore.updatePhase(laggingId, TaskPhase.COMPLETED);

        // The lagging done task must not shadow the genuinely active one.
        assertThat(taskStore.activeTasksForThread(thread.id()).stream().findFirst().map(Task::id))
                .hasValue(activeId);

        // Once the active task also completes (phase), the thread is idle —
        // no active task — even if its runtime status never flipped.
        taskStore.updatePhase(activeId, TaskPhase.COMPLETED);
        assertThat(taskStore.activeTasksForThread(thread.id())).isEmpty();
    }

    @Test
    void saveThreadCascadeDoesNotMirrorThreadLifetimeTokensOntoTheActiveTask()
    {
        // The thread's cost/tokens are LIFETIME-cumulative across the whole
        // task chain; the saveThread cascade must NOT copy them onto the
        // active task (that made a freshly-cut task inherit the thread's
        // entire 26M-token spend). The task keeps its own usage.
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING);
        store.saveThread(thread);
        Instant created = Instant.parse("2026-05-15T12:00:00Z");
        String taskId = UUID.randomUUID().toString();
        // The task's OWN usage — small.
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.IDLE, "main", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                /* cost */ 7L, /* tokensIn */ 11L, /* tokensOut */ 13L,
                null, created, null, null, null, null, null));

        // Persist the thread with a huge lifetime spend, triggering the cascade.
        Thread busy = new Thread(
                thread.id(), thread.kind(), thread.provider(), thread.agentSessionId(),
                thread.title(), ThreadStatus.RUNNING, thread.model(),
                /* cost */ 9_000L, /* tokensIn */ 26_000_000L, /* tokensOut */ 200_000L,
                thread.createdAt(), Instant.parse("2026-05-15T13:00:00Z"),
                null, null, thread.flow(), "ws-default", thread.workModel());
        store.saveThread(busy);

        Task got = taskStore.findTaskById(taskId).orElseThrow();
        // Tokens/cost preserved as the task's OWN, NOT the thread's lifetime.
        assertThat(got.tokensIn()).isEqualTo(11L);
        assertThat(got.tokensOut()).isEqualTo(13L);
        assertThat(got.costUsdMilli()).isEqualTo(7L);
        // Status IS still mirrored from the thread (lifecycle sync).
        assertThat(got.status()).isEqualTo(TaskStatus.RUNNING);
    }

    private static Thread newTask(ThreadKind kind, ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                UUID.randomUUID().toString(),
                kind,
                "claude-code",
                /* agentSessionId */ null,
                "Build daily standup feature",
                status,
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null);
    }

    private static Thread withTimestamps(Thread source, Instant created, Instant updated)
    {
        return new Thread(
                source.id(), source.kind(), source.provider(), source.agentSessionId(),
                source.title(), source.status(),
                source.model(), source.costUsdMilli(), source.tokensIn(), source.tokensOut(),
                created, updated,
                source.endedAt(), source.errorMessage(),
                source.flow(),
                "ws-default",
                source.workModel());
    }

    private static ThreadMessage message(String threadId, long seq, String role, String type, String contentJson)
    {
        return new ThreadMessage(
                UUID.randomUUID().toString(),
                threadId,
                /* taskId */ null,
                seq,
                role,
                type,
                contentJson,
                /* durationMs */ null,
                /* tokensIn */ null,
                /* tokensOut */ null,
                /* costUsdMilli */ null,
                Instant.parse("2026-05-15T12:00:00Z").plusSeconds(seq));
    }
}
