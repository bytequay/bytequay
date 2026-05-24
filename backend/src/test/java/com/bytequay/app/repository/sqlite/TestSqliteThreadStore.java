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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
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
                initial.activeTask());
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
                original.activeTask());
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
                0L, 0L, 0L, /* agentSessionId */ null, taskCreated, null, null, null));

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
                /* activeTask */ null);
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
                source.activeTask());
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
