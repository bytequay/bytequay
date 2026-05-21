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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
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

    @Test
    void roundtripsACliAgentTask()
    {
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING, 1234, "/tmp/log.jsonl");
        store.saveThread(thread);

        Optional<Thread> loaded = store.findThreadById(thread.id());
        assertThat(loaded).isPresent();
        Thread got = loaded.get();
        assertThat(got.id()).isEqualTo(thread.id());
        assertThat(got.kind()).isEqualTo(ThreadKind.CLI_AGENT);
        assertThat(got.status()).isEqualTo(ThreadStatus.RUNNING);
        assertThat(got.processPid()).isEqualTo(1234);
        assertThat(got.logPath()).isEqualTo("/tmp/log.jsonl");
        // metadata_json was dropped from the threads table in V72; the
        // record component still exists for wire compat and returns "{}".
        assertThat(got.metadataJson()).isEqualTo("{}");
    }

    @Test
    void roundtripsALogicLoopTaskWithNullCliFields()
    {
        Thread thread = newTask(ThreadKind.LOGIC_LOOP, ThreadStatus.PENDING, null, null);
        store.saveThread(thread);

        Thread got = store.findThreadById(thread.id()).orElseThrow();
        assertThat(got.kind()).isEqualTo(ThreadKind.LOGIC_LOOP);
        assertThat(got.processPid()).isNull();
        assertThat(got.logPath()).isNull();
        assertThat(got.agentSessionId()).isNull();
    }

    @Test
    void saveTaskUpdatesInPlaceWhenIdMatches()
    {
        Thread initial = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING, 99, "/tmp/x.jsonl");
        store.saveThread(initial);

        Thread updated = new Thread(
                initial.id(), initial.kind(), initial.provider(), "agent-session-abc",
                initial.title(), ThreadStatus.AWAITING, initial.workingDir(),
                initial.branchName(), initial.model(),
                /* costUsdMilli */ 12_345L, /* tokensIn */ 1_000L, /* tokensOut */ 2_000L,
                initial.processPid(), initial.logPath(),
                initial.createdAt(), Instant.parse("2026-05-15T13:00:00Z"),
                /* endedAt */ null, /* errorMessage */ null,
                initial.metadataJson(),
                initial.taskType(), initial.linkedPrNumber(), initial.linkedIssueNumber(),
                initial.worktreePath(), initial.localBranch());
        store.saveThread(updated);

        Thread got = store.findThreadById(initial.id()).orElseThrow();
        assertThat(got.status()).isEqualTo(ThreadStatus.AWAITING);
        assertThat(got.agentSessionId()).isEqualTo("agent-session-abc");
        assertThat(got.costUsdMilli()).isEqualTo(12_345L);
        assertThat(got.tokensIn()).isEqualTo(1_000L);
        assertThat(got.updatedAt()).isEqualTo(Instant.parse("2026-05-15T13:00:00Z"));
    }

    @Test
    void listTasksByStatusOrdersNewestFirstAndCapsToLimit()
    {
        Instant base = Instant.parse("2026-05-15T12:00:00Z");
        for (int i = 0; i < 5; i++) {
            Thread t = withTimestamps(
                    newTask(ThreadKind.CLI_AGENT, ThreadStatus.IDLE, 7000 + i, "/tmp/" + i + ".jsonl"),
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
        Thread thread = newTask(ThreadKind.CLI_AGENT, ThreadStatus.RUNNING, 4242, "/tmp/m.jsonl");
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
        Thread thread = newTask(ThreadKind.LOGIC_LOOP, ThreadStatus.RUNNING, null, null);
        store.saveThread(thread);

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

    private static Thread newTask(ThreadKind kind, ThreadStatus status, Integer pid, String logPath)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                UUID.randomUUID().toString(),
                kind,
                "claude-code",
                /* agentSessionId */ null,
                "Build daily standup feature",
                status,
                "/Users/jack.chen/IdeaProjects/bytequay",
                /* branchName */ "main",
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                pid,
                logPath,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                "{\"originPr\":\"trinodb/trino#42\"}",
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* worktreePath */ null,
                /* localBranch */ null);
    }

    private static Thread withTimestamps(Thread source, Instant created, Instant updated)
    {
        return new Thread(
                source.id(), source.kind(), source.provider(), source.agentSessionId(),
                source.title(), source.status(), source.workingDir(), source.branchName(),
                source.model(), source.costUsdMilli(), source.tokensIn(), source.tokensOut(),
                source.processPid(), source.logPath(), created, updated,
                source.endedAt(), source.errorMessage(), source.metadataJson(),
                source.taskType(), source.linkedPrNumber(), source.linkedIssueNumber(),
                source.worktreePath(), source.localBranch());
    }

    private static ThreadMessage message(String threadId, long seq, String role, String type, String contentJson)
    {
        return new ThreadMessage(
                UUID.randomUUID().toString(),
                threadId,
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
